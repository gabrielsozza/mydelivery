package com.mydelivery.job;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.WhatsappDesconexaoLog;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.UazapiClient;
import com.mydelivery.service.whatsapp.WhatsappService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Heartbeat ATIVO da instância. Chama {@code GET /instance/status} da Uazapi
 * pra cada instância CONECTADA em intervalos regulares.
 *
 * <p>Motivação: só confiar em webhook CONNECTION_UPDATE + msgs recebidas
 * deixa dois buracos:
 * <ol>
 *   <li>Shadow ban invisível: Uazapi diz "connected", WhatsApp não entrega.
 *   Ninguém tem como saber sem consultar {@code state} + comparar com histórico.</li>
 *   <li>Sessão limpa do lado do dono (WhatsApp Web/celular removeu
 *   dispositivo): Uazapi eventualmente reporta, mas até lá o sistema segue
 *   como CONECTADA no banco e sem tentar reconexão.</li>
 * </ol>
 *
 * <p>Estratégia:
 * <ul>
 *   <li>A cada 5min, pra cada instância CONECTADA + botAtivo, chama
 *   {@code consultarStatus} da Uazapi.</li>
 *   <li>Se resposta indica {@code state=close|connecting}, marca DESCONECTADA
 *   e loga QUEDA em auditoria (com motivo "heartbeat detectou state=X").</li>
 *   <li>Se HTTP 5xx ou timeout, incrementa
 *   {@code heartbeatsFalhadosSeguidos}. Se >= 3 seguidas, marca INSTAVEL e
 *   loga MARCADA_INSTAVEL. Isso captura o "Uazapi mente que está conectada".</li>
 *   <li>Se resposta OK, zera contador de falhas.</li>
 * </ul>
 *
 * <p><b>Throttle:</b> chama Uazapi 12x/hora × N instâncias. Pra 18 instâncias
 * são 216 requests/hora — bem dentro do plano contratado. Espaça 1s entre
 * chamadas no mesmo tick pra não estourar rate limit deles.
 *
 * <p><b>Fail-safe:</b> nunca lança. Job de monitoramento não pode quebrar
 * fluxo de webhooks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsappHeartbeatJob {

    private final WhatsappInstanceRepository repo;
    private final UazapiClient uazapiClient;
    private final WhatsappService whatsappService;

    /** Kill-switch. Padrão HABILITADO — heartbeat é medicamento leve. */
    @Value("${mydelivery.jobs.wa-heartbeat.ativo:true}")
    private boolean ativo;

    /** Máximo de falhas consecutivas antes de marcar INSTAVEL. */
    private static final int MAX_FALHAS_ANTES_INSTAVEL = 3;

    /**
     * Roda a cada 5min. InitialDelay generoso pra não conflitar com boot
     * sync (UazapiBootSyncService também consulta status no boot).
     */
    @Scheduled(fixedRate = 5L * 60_000, initialDelay = 3L * 60_000)
    public void tick() {
        if (!ativo) {
            log.debug("[WAHeartbeat] desabilitado — pulando");
            return;
        }
        var instancias = repo.findAll().stream()
                .filter(i -> i.getStatus() == WhatsappInstance.Status.CONECTADA)
                .filter(i -> Boolean.TRUE.equals(i.getBotAtivo()))
                .toList();
        if (instancias.isEmpty()) return;

        log.debug("[WAHeartbeat] avaliando {} instâncias conectadas", instancias.size());
        int ok = 0, falha = 0, marcadaInstavel = 0;
        for (WhatsappInstance inst : instancias) {
            try {
                var resultado = avaliarUma(inst);
                switch (resultado) {
                    case OK -> ok++;
                    case FALHA_SOFT -> falha++;
                    case MARCADA_INSTAVEL_HARD -> marcadaInstavel++;
                }
            } catch (Exception e) {
                log.warn("[WAHeartbeat] erro em {}: {}", inst.getInstanceName(), e.getMessage());
            }
            // Espaça 1s pra não hammer a Uazapi
            try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
        log.info("[WAHeartbeat] tick concluído — ok={}, soft-fail={}, marcadas instáveis={}",
                ok, falha, marcadaInstavel);
    }

    private enum Resultado { OK, FALHA_SOFT, MARCADA_INSTAVEL_HARD }

    @Transactional
    protected Resultado avaliarUma(WhatsappInstance inst) {
        String nome = inst.getInstanceName();
        LocalDateTime agora = LocalDateTime.now();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = uazapiClient.consultarStatus(nome);
            String state = extrairState(resp);
            log.debug("[WAHeartbeat][{}] state Uazapi = {}", nome, state);

            if ("open".equalsIgnoreCase(state)) {
                // Heartbeat OK — zera contadores.
                inst.setHeartbeatsFalhadosSeguidos(0);
                inst.setUltimoHeartbeatEm(agora);
                inst.setUltimoHeartbeatOk(true);
                repo.save(inst);
                return Resultado.OK;
            }

            // State != open. Uazapi discorda do nosso banco (achamos CONECTADA).
            // Rebate: marca DESCONECTADA + registra QUEDA.
            inst.setUltimoHeartbeatEm(agora);
            inst.setUltimoHeartbeatOk(false);
            inst.setHeartbeatsFalhadosSeguidos(inst.getHeartbeatsFalhadosSeguidos() + 1);
            inst.setStatus(WhatsappInstance.Status.DESCONECTADA);
            inst.setUltimaQuedaEm(agora);
            inst.setMotivoUltimaQueda("heartbeat detectou state=" + state);
            repo.save(inst);

            whatsappService.registrarEventoAuditoria(inst,
                    WhatsappDesconexaoLog.Tipo.HEARTBEAT_FALHOU,
                    "state=" + state, state, "CONECTADA", "DESCONECTADA",
                    null, "wa-hb-" + nome + "-" + System.currentTimeMillis());

            log.warn("[WAHeartbeat][{}] Uazapi reportou state={} — marcado DESCONECTADA", nome, state);
            return Resultado.MARCADA_INSTAVEL_HARD;

        } catch (Exception e) {
            // Falha soft: Uazapi API está com 5xx ou timeout. Incrementa
            // contador e só marca INSTAVEL se >= 3 consecutivas.
            inst.setUltimoHeartbeatEm(agora);
            inst.setUltimoHeartbeatOk(false);
            int novoContador = (inst.getHeartbeatsFalhadosSeguidos() == null ? 0
                    : inst.getHeartbeatsFalhadosSeguidos()) + 1;
            inst.setHeartbeatsFalhadosSeguidos(novoContador);
            log.warn("[WAHeartbeat][{}] falha #{} de consulta: {}", nome, novoContador, e.getMessage());

            if (novoContador >= MAX_FALHAS_ANTES_INSTAVEL) {
                inst.setStatus(WhatsappInstance.Status.DESCONECTADA);
                inst.setUltimaQuedaEm(agora);
                inst.setMotivoUltimaQueda("heartbeat: " + novoContador + " falhas seguidas");
                whatsappService.registrarEventoAuditoria(inst,
                        WhatsappDesconexaoLog.Tipo.MARCADA_INSTAVEL,
                        "heartbeat: " + novoContador + " falhas seguidas",
                        e.getMessage() == null ? null : e.getMessage().substring(0, Math.min(60, e.getMessage().length())),
                        "CONECTADA", "DESCONECTADA",
                        null, "wa-hb-" + nome + "-" + System.currentTimeMillis());
                repo.save(inst);
                log.error("[WAHeartbeat][{}] MARCADA INSTAVEL após {} falhas — HealthJob vai avaliar reconexão",
                        nome, novoContador);
                return Resultado.MARCADA_INSTAVEL_HARD;
            }
            repo.save(inst);
            return Resultado.FALHA_SOFT;
        }
    }

    /** Extrai state do formato Evolution { instance: { state: "open" } }. */
    @SuppressWarnings("unchecked")
    private String extrairState(Map<String, Object> resp) {
        if (resp == null) return "unknown";
        Object inst = resp.get("instance");
        if (inst instanceof Map<?, ?> instMap) {
            Object state = ((Map<String, Object>) instMap).get("state");
            return state == null ? "unknown" : state.toString();
        }
        return "unknown";
    }
}
