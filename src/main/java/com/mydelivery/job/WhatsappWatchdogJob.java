package com.mydelivery.job;

import com.mydelivery.service.whatsapp.UazapiClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.EvolutionClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * WATCHDOG PING ATIVO (Jul/2026)
 *
 * ═══════════════════════════════════════════════════════════════════════
 * PROBLEMA que resolve
 * ═══════════════════════════════════════════════════════════════════════
 *
 * "Instância zumbi": no banco status=CONECTADA, mas na verdade a sessão
 * Baileys travou. Sintomas:
 *   - Cliente manda WhatsApp → nada acontece
 *   - Painel do restaurante mostra "Conectado"
 *   - ultimaMensagemRecebidaEm parece OK (webhooks antigos)
 *
 * O sistema atual detecta isso SÓ passivamente (via ausência prolongada
 * de webhook — mínimo 60min). Restaurante perde 1h de vendas antes.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * SOLUÇÃO
 * ═══════════════════════════════════════════════════════════════════════
 *
 * A cada 30min, pra cada instância CONECTADA:
 *   1. GET /instance/connectionState — pergunta ATIVAMENTE se a sessão
 *      Baileys tá viva (não depende de webhook).
 *   2. Se retorna "open" → registra sucesso. Zera contador de falhas.
 *   3. Se retorna "close"/erro → registra falha. Se 2 falhas SEGUIDAS,
 *      marca a instância como suspeita de zumbi (log CRIT + status vai
 *      pra DESCONECTADA — health job pega e reconecta).
 *
 * Fora do pico (11-13h, 18-22h). Circuit breaker respeita: se Evolution
 * fora, watchdog não faz nada até CB fechar.
 *
 * Storage do contador de falhas: em memória (ConcurrentHashMap por
 * instanceName). Não persiste — reset ao reiniciar backend é aceito
 * (falso negativo de 30min no pior caso).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsappWatchdogJob {

    private final WhatsappInstanceRepository repo;
    private final UazapiClient evolutionClient;

    /**
     * Kill-switch. Padrão ON — detecção precoce de zumbi é feature crítica.
     * Só desativa se identificar falso positivo em produção.
     */
    @org.springframework.beans.factory.annotation.Value("${mydelivery.jobs.wa-watchdog.ativo:true}")
    private boolean ativo;

    /**
     * Máximo de pings falhos seguidos antes de marcar como suspeita.
     * Jul/2026 v3: 2 → 3. Com tick de 30min, 2 falhas = 60min de tolerância,
     * o que era curto demais. Evolution/Baileys em rush pode ficar lento
     * por 60-90min e recuperar sozinho. 3 falhas = 90min mínimo, evita
     * flag individual quando o problema é global.
     */
    private static final int MAX_FALHAS_SEGUIDAS = 3;

    /**
     * Se MAIS de {@code SAFETY_FALHAS_MASSA_PCT}% das instâncias falharem
     * ping no mesmo tick, é problema global da Evolution — NÃO marcamos NENHUMA
     * como zumbi individual. Isso evita o cenário "Evolution VPS caiu 30min →
     * watchdog matou todas as 9 instâncias".
     * 50% = suficientemente conservador. Se 5 de 9 falham, é infra, não app.
     */
    private static final int SAFETY_FALHAS_MASSA_PCT = 50;

    /**
     * Contador de falhas consecutivas por instância. Reseta em qualquer
     * ping OK. Chegou em MAX_FALHAS_SEGUIDAS → intervir.
     */
    private static final ConcurrentHashMap<String, Integer> FALHAS_SEGUIDAS = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 30L * 60 * 1000, initialDelay = 3L * 60 * 1000)
    public void tick() {
        if (!ativo) return;

        // Não perturba a Evolution nem faz side-effect no meio do pico.
        // Se sessão zumbi cair EXATAMENTE no pico, esperamos até 30min pra
        // detectar — melhor que estourar restart no meio do jantar.
        LocalTime agora = LocalTime.now();
        int h = agora.getHour();
        if ((h >= 11 && h <= 13) || (h >= 18 && h <= 22)) {
            log.debug("[Watchdog] em pico ({}h) — pulando tick", h);
            return;
        }

        // Se circuit breaker aberto, Evolution tá fora — não vale fazer PING.
        if (evolutionClient.circuitBreakerAberto()) {
            log.info("[Watchdog] circuit breaker aberto — pulando tick (Evolution fora)");
            return;
        }

        var conectadas = repo.findAll().stream()
                .filter(i -> i.getStatus() == WhatsappInstance.Status.CONECTADA)
                .toList();

        if (conectadas.isEmpty()) {
            log.debug("[Watchdog] nenhuma instância CONECTADA pra checar");
            return;
        }

        // PASSO 1: pinga TODAS as instâncias primeiro. Coleta resultados.
        // Só depois decide o que fazer, permitindo detectar padrão de outage
        // global (muitas falhas no mesmo tick = infra, não zumbi individual).
        java.util.List<String> falharam = new java.util.ArrayList<>();
        java.util.List<WhatsappInstance> falharamInst = new java.util.ArrayList<>();
        java.util.Set<String> tratadasConnecting = new java.util.HashSet<>();
        int ok = 0;
        for (WhatsappInstance inst : conectadas) {
            String nome = inst.getInstanceName();
            String state = pingarComEstado(nome);
            if ("open".equalsIgnoreCase(state)) {
                Integer previas = FALHAS_SEGUIDAS.remove(nome);
                ok++;
                if (previas != null && previas > 0) {
                    log.info("[Watchdog] {} recuperou após {} falhas", nome, previas);
                }
                CONNECTING_DESDE.remove(nome);
            } else if ("cb_open".equals(state)) {
                // Circuit aberto — não conta nem como sucesso nem falha
                CONNECTING_DESDE.remove(nome);
            } else {
                // Estado ruim ("connecting", "close", null). Verifica se é
                // "connecting travado" — se sim, restart forçado e não conta
                // como falha comum (pra não acumular no contador de zumbi
                // enquanto o restart faz efeito).
                boolean restartFeito = tratarConnectingTravado(nome, state, inst);
                if (restartFeito) {
                    tratadasConnecting.add(nome);
                    // Zera contador de falhas — o restart é a intervenção
                    FALHAS_SEGUIDAS.remove(nome);
                } else {
                    falharam.add(nome);
                    falharamInst.add(inst);
                }
            }
        }

        // PASSO 2: safety anti-cascade. Se >= 50% das instâncias falharam
        // no MESMO tick, é problema global da Evolution/rede — NÃO marcamos
        // NENHUMA como zumbi. Só logamos alerta pra admin investigar.
        // Isso previne o cenário "Evolution VPS caiu 30min → watchdog matou
        // todas as 9 instâncias" que já aconteceu.
        int pctFalha = conectadas.size() == 0 ? 0 : (falharam.size() * 100) / conectadas.size();
        if (pctFalha >= SAFETY_FALHAS_MASSA_PCT && conectadas.size() >= 3) {
            log.error("[Watchdog:SAFETY] OUTAGE GLOBAL suspeito — {}% ({}/{}) das instâncias falharam ping " +
                    "no mesmo tick. NÃO marcando nenhuma como zumbi (provável problema Evolution/rede). " +
                    "Instâncias afetadas: {}",
                    pctFalha, falharam.size(), conectadas.size(), falharam);
            // NÃO incrementa contadores — o que aconteceu foi infra, não zumbi.
            // Se Evolution recuperar até o próximo tick, contadores zeram sozinhos.
            return;
        }

        // PASSO 3: modo normal — incrementa contador de cada falha e marca como
        // zumbi quando atinge MAX_FALHAS_SEGUIDAS.
        int zumbi = 0;
        for (int i = 0; i < falharam.size(); i++) {
            String nome = falharam.get(i);
            WhatsappInstance inst = falharamInst.get(i);
            int consec = FALHAS_SEGUIDAS.merge(nome, 1, Integer::sum);
            if (consec >= MAX_FALHAS_SEGUIDAS) {
                zumbi++;
                log.error("[Watchdog] ZUMBI DETECTADO — {} falhou {} pings seguidos. " +
                        "Marcando status=DESCONECTADA pra HealthJob tentar reconectar.",
                        nome, consec);
                try {
                    inst.setStatus(WhatsappInstance.Status.DESCONECTADA);
                    inst.setMotivoUltimaQueda("Watchdog: sessão zumbi (Evolution deixou de responder ao ping)");
                    inst.setUltimaQuedaEm(LocalDateTime.now());
                    repo.save(inst);
                } catch (Exception e) {
                    log.error("[Watchdog] falha ao marcar {} como DESCONECTADA: {}", nome, e.getMessage());
                }
                FALHAS_SEGUIDAS.put(nome, 0);
            } else {
                log.warn("[Watchdog] {} falhou ping #{} (precisa de {} pra intervir)",
                        nome, consec, MAX_FALHAS_SEGUIDAS);
            }
        }

        log.info("[Watchdog] tick — checadas={} ok={} falhou={} zumbi={} connecting_restart={} pctFalha={}%",
                conectadas.size(), ok, falharam.size(), zumbi, tratadasConnecting.size(), pctFalha);
    }

    /**
     * Consulta ativa o estado da sessão Baileys via Evolution.
     * Retorna true se sessão está aberta ("open"). False em qualquer
     * outro cenário (erro de rede, timeout, "close", "connecting").
     */
    private boolean pingar(String instanceName) {
        return "open".equalsIgnoreCase(pingarComEstado(instanceName));
    }

    /**
     * Versão que devolve o STATE cru — "open"/"close"/"connecting"/null.
     * Usado pra detectar "connecting travado" e disparar restart forte
     * em vez de só marcar DESCONECTADA (que não sara sessão presa no
     * meio do handshake — Baileys precisa de reset completo).
     * "cb_open" = circuit breaker Evolution aberto (não é falha nossa,
     * não conta como zumbi mas também não confirma estado).
     */
    private String pingarComEstado(String instanceName) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> state = evolutionClient.consultarStatus(instanceName);
            String s = extrairState(state);
            if (s == null || s.isBlank()) return null;
            log.debug("[Watchdog] {} ping retornou state={}", instanceName, s);
            return s.toLowerCase();
        } catch (EvolutionClient.EvolutionCircuitOpenException cb) {
            return "cb_open";
        } catch (Exception e) {
            log.warn("[Watchdog] ping {} falhou: {}", instanceName, e.getMessage());
            return null;
        }
    }

    /**
     * Detecção de "connecting travado".
     *
     * Chave = instanceName. Valor = LocalDateTime da primeira vez que vimos
     * essa instância em connecting. Se persistir >MAX_CONNECTING_MINUTOS
     * sem migrar pra open, forçamos POST /instance/restart — mais forte
     * que marcar como DESCONECTADA porque o Baileys presa no handshake
     * não é curada só desconectando + reconectando pelo HealthJob (que
     * tenta pelo mesmo processo Baileys travado).
     *
     * Reseta ao ver estado != "connecting" (open/close = fluxo normal).
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, LocalDateTime> CONNECTING_DESDE
            = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Jul/2026 v4: 10 → 30min. Uazapi rotineiramente passa por "connecting" em
     * ciclos de reconexão do WhatsApp (auth handshake, refresh de sessão). 10min
     * cortava sessões saudáveis no meio do handshake. 30min só age em travamento
     * real (Uazapi normalmente resolve em ≤15min). Restart agora é SOFT (só
     * /connect, sem disconnect), então até se disparar aqui não invalida
     * pareamento — mas menos intervenção = menos suspeição do WA.
     */
    private static final int MAX_CONNECTING_MINUTOS = 30;

    /**
     * Se instância está travada em connecting há muito, força restart forte.
     * Retorna true se agiu (restart disparado) — o caller pode pular a
     * lógica normal de "marcar como zumbi" pra esse instance nesse tick.
     */
    private boolean tratarConnectingTravado(String instanceName, String state, WhatsappInstance inst) {
        if (!"connecting".equalsIgnoreCase(state)) {
            // Não está em connecting — limpa o tracker se havia entrada
            CONNECTING_DESDE.remove(instanceName);
            return false;
        }
        LocalDateTime desde = CONNECTING_DESDE.computeIfAbsent(instanceName, k -> LocalDateTime.now());
        long minutos = java.time.Duration.between(desde, LocalDateTime.now()).toMinutes();
        if (minutos < MAX_CONNECTING_MINUTOS) {
            log.info("[Watchdog] {} em connecting há {}min (limite {}min) — aguardando", instanceName, minutos, MAX_CONNECTING_MINUTOS);
            return false;
        }
        // Passou do limite — força restart via Evolution
        log.error("[Watchdog:CONNECTING_TRAVADO] {} está em connecting há {}min. Forçando /instance/restart.",
                instanceName, minutos);
        try {
            evolutionClient.restart(instanceName);
            CONNECTING_DESDE.remove(instanceName); // reseta pra dar tempo do restart tomar efeito
            try {
                inst.setMotivoUltimaQueda("Watchdog: connecting travado por " + minutos + "min — restart forçado");
                inst.setUltimaQuedaEm(LocalDateTime.now());
                repo.save(inst);
            } catch (Exception ignore) {}
            return true;
        } catch (Exception e) {
            log.error("[Watchdog:CONNECTING_TRAVADO] falha ao reiniciar {}: {}", instanceName, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private String extrairState(Map<String, Object> resp) {
        if (resp == null) return null;
        Object inst = resp.get("instance");
        if (inst instanceof Map<?, ?> m) {
            Object s = ((Map<String, Object>) m).get("state");
            return s == null ? null : s.toString();
        }
        Object s = resp.get("state");
        return s == null ? null : s.toString();
    }
}
