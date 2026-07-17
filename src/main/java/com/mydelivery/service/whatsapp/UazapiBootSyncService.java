package com.mydelivery.service.whatsapp;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappInstanceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sincronização resiliente Uazapi ↔ BD (Jul/2026).
 *
 * <p><b>Problema:</b> a cada restart/deploy do backend, o painel mostrava as
 * instâncias como desconectadas mesmo com a sessão ativa no Uazapi. O BD
 * refletia o "último estado que o backend viu", não o estado real. Com o
 * webhook global do Uazapi por natureza <b>não é reenviado</b> depois de
 * uma queda do backend, o único sinal era o próximo evento de conexão —
 * que às vezes só chegava horas depois. Enquanto isso: bot silencioso,
 * lojas achando que caiu, dono desesperado gerando QR novo (consumindo
 * slot da Uazapi à toa).
 *
 * <p><b>Fix:</b> Uazapi é <b>a fonte da verdade</b>. Após qualquer boot:
 * <ol>
 *   <li>Consulta {@code GET /instance/all} (endpoint admin).</li>
 *   <li>Pra cada instância do Uazapi, faz upsert do BD: token,
 *       phone (via {@code jid.user}), status (open/close/connecting).</li>
 *   <li>Instâncias do BD que <b>não aparecem</b> no Uazapi ficam marcadas
 *       como {@code DESCONECTADA} — não somem, dono usa o botão pra reconectar.</li>
 *   <li>Log detalhado: total encontrado, quantas sincronizadas, quantas
 *       divergências corrigidas, quantas falhas de comunicação, tempo total.</li>
 * </ol>
 *
 * <p><b>Frequência:</b> executado no boot + a cada 10 minutos pra corrigir
 * drift caso um webhook seja perdido. Se Uazapi estiver fora do ar, sync
 * é pulado (não marca tudo como desconectado — fail-safe).
 *
 * <p><b>Ordem no boot:</b> {@code @Order(LOWEST_PRECEDENCE)} garante que
 * rode DEPOIS do {@code UazapiClient.bootstrapTokens} — assim o cache de
 * tokens já tá quente quando o sync precisar chamar {@code /instance/status}
 * pra alguma instância órfã.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UazapiBootSyncService {

    private final UazapiClient uazapiClient;
    private final WhatsappInstanceRepository repo;

    /**
     * Anti-flap: exige {@code MIN_FETCHES_DISCONNECTED} fetches consecutivos vendo
     * a instância como desconectada antes de mudar status no BD. Uazapi tem
     * blips transientes (isConnected=false por 10-30s durante keepalive do WA)
     * que faziam o sync anterior marcar DESCONECTADA e disparar cascata de
     * restarts. Chave = instanceName; valor = contador de fetches consecutivos
     * "ruins". Reseta em qualquer fetch OK. Só marca DESCONECTADA no 2º ruim seguido.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> FLAP_DESCONECTADA =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Contador análogo para "sumiu do /instance/all" (Uazapi pode devolver lista
     *  parcial em fetches transitórios). */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> FLAP_SUMIU =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final int MIN_FETCHES_DISCONNECTED = 2;

    /** Se mais de {@code SAFETY_SUMIU_MASSA_PCT}% das instâncias CONECTADAs do BD
     *  não aparecerem no /instance/all, é sinal de fetch parcial/quebrado do Uazapi.
     *  Skip a etapa de marcar como sumida — mantém status atual. */
    private static final int SAFETY_SUMIU_MASSA_PCT = 30;

    /**
     * Roda no boot. {@code @Order(LOWEST_PRECEDENCE)} garante que rode DEPOIS
     * do bootstrap de tokens do {@link UazapiClient} (evita 401 se sync
     * precisar chamar rota da instância).
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void sincronizarNoBoot() {
        log.info("[UazapiSync] boot — iniciando sincronização full (Uazapi como fonte da verdade)");
        sincronizarComLog("boot");
    }

    /**
     * Roda a cada 10 min. Fixa drift: webhook Uazapi <b>não retransmite</b>
     * eventos após um outage do backend — só o polling ativo pega a
     * divergência. Também compensa se webhook global for reconfigurado no
     * Uazapi e ainda não estiver chegando.
     */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    public void sincronizarPeriodico() {
        sincronizarComLog("periodic");
    }

    @Transactional
    void sincronizarComLog(String origem) {
        long inicio = System.currentTimeMillis();
        try {
            log.info("[UazapiSync][{}] ═══ INÍCIO ═══", origem);

            List<Map<String, Object>> uazapi;
            try {
                uazapi = uazapiClient.fetchInstancesRaw();
            } catch (Exception e) {
                log.error("[UazapiSync][{}] falha ao contatar Uazapi: {} — sync ABORTADO (fail-safe: nada é marcado como desconectado)",
                        origem, e.getMessage());
                return;
            }
            long msFetch = System.currentTimeMillis() - inicio;
            log.info("[UazapiSync][{}] {} instâncias devolvidas pelo Uazapi em {}ms",
                    origem, uazapi.size(), msFetch);

            if (uazapi.isEmpty()) {
                // NÃO marca tudo como desconectado — pode ser que o Uazapi
                // ainda esteja subindo ou tenha resetado. Espera o próximo tick.
                log.warn("[UazapiSync][{}] Uazapi devolveu lista VAZIA — pulando ciclo (não vai marcar instâncias como DESCONECTADA)",
                        origem);
                return;
            }

            int sincronizadas = 0;
            int divergenciasCorrigidas = 0;
            int criadosPhones = 0;
            int atualizadosTokens = 0;
            int naoAchadasNoBd = 0;
            int falhasIndividuais = 0;

            java.util.Set<String> nomesVistosUazapi = new java.util.HashSet<>();

            for (Map<String, Object> u : uazapi) {
                try {
                    String name = str(u, "name");
                    if (name == null || name.isBlank()) {
                        log.warn("[UazapiSync][{}] instância Uazapi sem 'name' — payload keys={}",
                                origem, u.keySet());
                        continue;
                    }
                    nomesVistosUazapi.add(name);

                    Optional<WhatsappInstance> optInst = repo.findByInstanceName(name);
                    if (optInst.isEmpty()) {
                        naoAchadasNoBd++;
                        log.warn("[UazapiSync][{}] instância {} existe no Uazapi mas NÃO no BD — provavelmente foi criada fora do fluxo; ignorando",
                                origem, name);
                        continue;
                    }
                    WhatsappInstance inst = optInst.get();

                    // ── Token: se o Uazapi devolveu e o BD tá vazio ou diferente, atualiza
                    String tokenUazapi = str(u, "token");
                    if (tokenUazapi == null) tokenUazapi = str(u, "apitoken");
                    if (tokenUazapi != null && !tokenUazapi.isBlank()
                            && !tokenUazapi.equals(inst.getInstanceToken())) {
                        inst.setInstanceToken(tokenUazapi);
                        atualizadosTokens++;
                        log.info("[UazapiSync][{}] token atualizado pra {}",
                                origem, name);
                    }

                    // ── Phone: extrai jid.user (formato "5527988387661") e sincroniza
                    String phoneUazapi = extrairPhone(u);
                    if (phoneUazapi != null && !phoneUazapi.isBlank()
                            && !phoneUazapi.equals(inst.getPhone())) {
                        inst.setPhone(phoneUazapi);
                        criadosPhones++;
                        log.info("[UazapiSync][{}] phone {} associado à instância {}",
                                origem, phoneUazapi, name);
                    }

                    // ── Status: Uazapi = fonte da verdade, mas com anti-flap
                    WhatsappInstance.Status statusUazapi = interpretarStatus(u);
                    WhatsappInstance.Status statusBd = inst.getStatus();

                    // ANTI-FLAP (Jul/2026): Uazapi tem blips transientes (isConnected=false
                    // por 10-30s durante ping-pong WA). Antes: sync marcava DESCONECTADA
                    // imediatamente → HealthJob disparava restart cascata → destruía sessão.
                    // Agora: exige 2 fetches seguidos vendo DESCONECTADA antes de marcar.
                    if (statusUazapi == WhatsappInstance.Status.CONECTADA) {
                        // Fetch OK — reseta contador de flap
                        FLAP_DESCONECTADA.remove(name);
                    } else if (statusUazapi == WhatsappInstance.Status.DESCONECTADA
                            && statusBd == WhatsappInstance.Status.CONECTADA) {
                        int flap = FLAP_DESCONECTADA.merge(name, 1, Integer::sum);
                        if (flap < MIN_FETCHES_DISCONNECTED) {
                            log.info("[UazapiSync][{}] {} vira DESCONECTADA no fetch #{} — aguardando confirmação (min={})",
                                    origem, name, flap, MIN_FETCHES_DISCONNECTED);
                            // Pula a mudança neste ciclo — mantém CONECTADA. Se próximo fetch
                            // confirmar, entra abaixo.
                            statusUazapi = null;
                        } else {
                            log.warn("[UazapiSync][{}] {} confirmada DESCONECTADA após {} fetches seguidos — aplicando",
                                    origem, name, flap);
                            FLAP_DESCONECTADA.remove(name);
                        }
                    }

                    if (statusUazapi != null && statusUazapi != statusBd) {
                        // RESPEITA intenção do usuário: se ele desconectou manual,
                        // NÃO promove de volta pra CONECTADA sem clique novo.
                        boolean donoDesconectouManual = Boolean.TRUE.equals(inst.getDesconectadoManualmente())
                                && statusBd == WhatsappInstance.Status.DESCONECTADA;

                        if (donoDesconectouManual && statusUazapi == WhatsappInstance.Status.CONECTADA) {
                            log.debug("[UazapiSync][{}] {} está CONECTADA no Uazapi mas dono desconectou manual — mantendo DESCONECTADA",
                                    origem, name);
                        } else {
                            log.info("[UazapiSync][{}] DIVERGÊNCIA em {}: BD={} → Uazapi={} — corrigindo",
                                    origem, name, statusBd, statusUazapi);
                            inst.setStatus(statusUazapi);
                            if (statusUazapi == WhatsappInstance.Status.CONECTADA) {
                                if (inst.getConectadoEm() == null) inst.setConectadoEm(LocalDateTime.now());
                                // FIX Jul/2026: garantir sessaoIniciadaEm no boot sync
                                // se essa instância estava conectada mas nunca foi setada
                                // (migração de bases antigas).
                                if (inst.getSessaoIniciadaEm() == null) inst.setSessaoIniciadaEm(inst.getConectadoEm());
                                inst.setQrCode(null);
                                inst.setQrExpiraEm(null);
                                inst.setDesconectadoManualmente(false);
                                inst.setTentativasReconexaoSeguidas(0);
                            } else if (statusUazapi == WhatsappInstance.Status.DESCONECTADA) {
                                inst.setUltimaQuedaEm(LocalDateTime.now());
                                if (inst.getMotivoUltimaQueda() == null) {
                                    inst.setMotivoUltimaQueda("Sync Uazapi (boot/periodic): estado real=desconectada");
                                }
                            }
                            divergenciasCorrigidas++;
                        }
                    }

                    repo.save(inst);
                    sincronizadas++;
                } catch (Exception e) {
                    falhasIndividuais++;
                    log.warn("[UazapiSync][{}] erro sincronizando instância — payload={}: {}",
                            origem, u, e.getMessage());
                }
            }

            // ── Instâncias no BD que o Uazapi NÃO conhece mais.
            // SAFETY (Jul/2026): Uazapi pode devolver lista PARCIAL em fetches
            // transientes (VPS lento, timeout do Baileys). Se >30% das CONECTADAs
            // do BD sumirem no mesmo fetch, é fetch quebrado — NÃO mexe em ninguém.
            // ANTI-FLAP: exige 2 fetches seguidos confirmando o sumiço.
            int marcadasSumidas = 0;
            var todasBd = repo.findAll();
            long conectadasNoBd = todasBd.stream()
                    .filter(i -> i.getStatus() == WhatsappInstance.Status.CONECTADA)
                    .count();
            long sumiramCandidatas = todasBd.stream()
                    .filter(i -> i.getStatus() == WhatsappInstance.Status.CONECTADA)
                    .filter(i -> i.getInstanceName() != null)
                    .filter(i -> !nomesVistosUazapi.contains(i.getInstanceName()))
                    .count();
            int pctSumiram = conectadasNoBd == 0 ? 0 : (int) ((sumiramCandidatas * 100) / conectadasNoBd);
            if (pctSumiram >= SAFETY_SUMIU_MASSA_PCT && conectadasNoBd >= 3) {
                log.error("[UazapiSync][{}] SAFETY: {}% ({}/{}) das CONECTADAs sumiram do /instance/all no mesmo fetch — provavelmente lista parcial do Uazapi. NÃO marcando ninguém como sumida.",
                        origem, pctSumiram, sumiramCandidatas, conectadasNoBd);
            } else {
                for (WhatsappInstance inst : todasBd) {
                    if (inst.getInstanceName() == null) continue;
                    if (nomesVistosUazapi.contains(inst.getInstanceName())) {
                        // Voltou a aparecer — zera contador de flap.
                        FLAP_SUMIU.remove(inst.getInstanceName());
                        continue;
                    }
                    if (inst.getStatus() == WhatsappInstance.Status.CONECTADA) {
                        int flap = FLAP_SUMIU.merge(inst.getInstanceName(), 1, Integer::sum);
                        if (flap < MIN_FETCHES_DISCONNECTED) {
                            log.info("[UazapiSync][{}] {} sumiu do Uazapi no fetch #{} — aguardando confirmação (min={})",
                                    origem, inst.getInstanceName(), flap, MIN_FETCHES_DISCONNECTED);
                            continue;
                        }
                        log.warn("[UazapiSync][{}] instância {} confirmada SUMIDA após {} fetches — marcando DESCONECTADA",
                                origem, inst.getInstanceName(), flap);
                        inst.setStatus(WhatsappInstance.Status.DESCONECTADA);
                        inst.setUltimaQuedaEm(LocalDateTime.now());
                        inst.setMotivoUltimaQueda("Instância sumiu do Uazapi (2+ fetchInstances seguidos)");
                        repo.save(inst);
                        marcadasSumidas++;
                        FLAP_SUMIU.remove(inst.getInstanceName());
                    }
                }
            }

            long total = System.currentTimeMillis() - inicio;
            log.info("[UazapiSync][{}] ═══ FIM em {}ms — {} do Uazapi | {} sincronizadas | {} divergências corrigidas | {} phones associados | {} tokens atualizados | {} não estavam no BD | {} sumiram do Uazapi | {} falhas ═══",
                    origem, total, uazapi.size(), sincronizadas, divergenciasCorrigidas,
                    criadosPhones, atualizadosTokens, naoAchadasNoBd, marcadasSumidas, falhasIndividuais);
        } catch (Exception e) {
            log.error("[UazapiSync][{}] erro inesperado no sync: {}", origem, e.getMessage(), e);
        }
    }

    /**
     * Interpreta o status devolvido pelo /instance/all da Uazapi.
     * <p>Uazapi usa campos {@code isConnected} e {@code isLogged} (boolean).
     * Alguns endpoints devolvem {@code status} como string ("connected",
     * "disconnected", "connecting"). Cobrimos ambos.
     */
    private WhatsappInstance.Status interpretarStatus(Map<String, Object> u) {
        // Prioridade 1: booleans explícitos
        Object isConnected = u.get("isConnected");
        Object isLogged = u.get("isLogged");
        if (isConnected instanceof Boolean || isLogged instanceof Boolean) {
            boolean conn = Boolean.TRUE.equals(isConnected);
            boolean log = Boolean.TRUE.equals(isLogged);
            if (conn && log) return WhatsappInstance.Status.CONECTADA;
            if (conn || log) return null; // "connecting" — não mexe
            return WhatsappInstance.Status.DESCONECTADA;
        }
        // Prioridade 2: string
        String status = str(u, "status");
        if (status == null) status = str(u, "state");
        if (status == null) return null;
        String s = status.toLowerCase();
        if (s.contains("connect") && !s.contains("dis") && !s.contains("ing")) {
            return WhatsappInstance.Status.CONECTADA;
        }
        if (s.equals("open")) return WhatsappInstance.Status.CONECTADA;
        if (s.contains("dis") || s.equals("close") || s.equals("closed")) {
            return WhatsappInstance.Status.DESCONECTADA;
        }
        return null; // desconhecido — não mexe
    }

    /**
     * Extrai o phone do payload /instance/all. Uazapi geralmente devolve em
     * {@code jid.user} ("5527988387661") ou {@code owner} (mesmo formato) ou
     * {@code number}. Aceita qualquer um.
     */
    @SuppressWarnings("unchecked")
    private String extrairPhone(Map<String, Object> u) {
        Object jid = u.get("jid");
        if (jid instanceof Map<?, ?> jm) {
            Object user = ((Map<String, Object>) jm).get("user");
            if (user != null) {
                String s = user.toString();
                if (!s.isBlank() && s.matches("\\d+")) return s;
            }
        }
        String[] candidatos = { "number", "phone", "owner", "wuid" };
        for (String k : candidatos) {
            Object v = u.get(k);
            if (v == null) continue;
            String s = v.toString().replaceAll("[^0-9]", "");
            if (s.length() >= 10 && s.length() <= 15) return s;
        }
        return null;
    }

    private String str(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
}
