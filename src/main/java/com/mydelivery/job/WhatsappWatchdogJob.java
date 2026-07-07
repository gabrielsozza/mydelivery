package com.mydelivery.job;

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
    private final EvolutionClient evolutionClient;

    /**
     * Kill-switch. Padrão ON — detecção precoce de zumbi é feature crítica.
     * Só desativa se identificar falso positivo em produção.
     */
    @org.springframework.beans.factory.annotation.Value("${mydelivery.jobs.wa-watchdog.ativo:true}")
    private boolean ativo;

    /** Máximo de pings falhos seguidos antes de marcar como suspeita. */
    private static final int MAX_FALHAS_SEGUIDAS = 2;

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

        int ok = 0, falhou = 0, zumbi = 0;
        for (WhatsappInstance inst : conectadas) {
            String nome = inst.getInstanceName();
            boolean pingOk = pingar(nome);
            if (pingOk) {
                // Zera contador — recuperou. remove() devolve o valor anterior
                // (ou null se nunca falhou) num único hit atômico.
                Integer previas = FALHAS_SEGUIDAS.remove(nome);
                ok++;
                if (previas != null && previas > 0) {
                    log.info("[Watchdog] {} recuperou após {} falhas", nome, previas);
                }
            } else {
                int consec = FALHAS_SEGUIDAS.merge(nome, 1, Integer::sum);
                falhou++;
                if (consec >= MAX_FALHAS_SEGUIDAS) {
                    zumbi++;
                    log.error("[Watchdog] ZUMBI DETECTADO — {} falhou {} pings seguidos. " +
                            "Marcando status=DESCONECTADA pra HealthJob tentar reconectar.",
                            nome, consec);
                    try {
                        // Marcar como DESCONECTADA faz HealthJob pegar no próximo
                        // tick e disparar o fluxo de reconexão normal (respeitando
                        // cooldown, warmup, pico, tudo). Não chamamos restart aqui
                        // pra centralizar decisão de reconexão no HealthJob.
                        inst.setStatus(WhatsappInstance.Status.DESCONECTADA);
                        inst.setMotivoUltimaQueda("Watchdog: sessão zumbi (Evolution deixou de responder ao ping)");
                        inst.setUltimaQuedaEm(LocalDateTime.now());
                        repo.save(inst);
                    } catch (Exception e) {
                        log.error("[Watchdog] falha ao marcar {} como DESCONECTADA: {}", nome, e.getMessage());
                    }
                    // Zera contador — HealthJob agora é dono da reconexão
                    FALHAS_SEGUIDAS.put(nome, 0);
                } else {
                    log.warn("[Watchdog] {} falhou ping #{} (precisa de {} pra intervir)",
                            nome, consec, MAX_FALHAS_SEGUIDAS);
                }
            }
        }

        log.info("[Watchdog] tick — checadas={} ok={} falhou={} zumbi={}",
                conectadas.size(), ok, falhou, zumbi);
    }

    /**
     * Consulta ativa o estado da sessão Baileys via Evolution.
     * Retorna true se sessão está aberta ("open"). False em qualquer
     * outro cenário (erro de rede, timeout, "close", "connecting").
     */
    private boolean pingar(String instanceName) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> state = evolutionClient.consultarStatus(instanceName);
            String s = extrairState(state);
            if ("open".equalsIgnoreCase(s)) return true;
            log.debug("[Watchdog] {} ping retornou state={}", instanceName, s);
            return false;
        } catch (EvolutionClient.EvolutionCircuitOpenException cb) {
            // Circuit aberto = Evolution fora. NÃO conta como falha da
            // instância — é problema global. Silêncio.
            return true;
        } catch (Exception e) {
            log.warn("[Watchdog] ping {} falhou: {}", instanceName, e.getMessage());
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
