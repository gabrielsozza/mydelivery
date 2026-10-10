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

            // /instance/status pode responder "connecting" durante keepalive,
            // ou usar um token antigo. Confirme no inventário administrativo
            // antes de remover uma sessão que ainda está ativa no provedor.
            Boolean conectadaNoInventario = conexaoNoInventario(nome);
            if (conectadaNoInventario == null) {
                throw new IllegalStateException("não foi possível confirmar a sessão no inventário do provedor");
            }
            if (conectadaNoInventario) {
                inst.setUltimoHeartbeatEm(agora);
                inst.setUltimoHeartbeatOk(false);
                inst.setHeartbeatsFalhadosSeguidos(0);
                repo.save(inst);
                log.info("[WAHeartbeat][{}] status={} transitório; inventário confirma conexão ativa", nome, state);
                return Resultado.FALHA_SOFT;
            }

            // Ambas as consultas concordam que a sessão caiu.
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

            // Erro de autenticação/rede não prova desconexão do WhatsApp.
            // Mantém o estado no banco até o inventário confirmar a queda.
            if (novoContador >= MAX_FALHAS_ANTES_INSTAVEL)
                log.error("[WAHeartbeat][{}] {} consultas falharam; mantendo CONECTADA até confirmação do provedor",
                        nome, novoContador);
            repo.save(inst);
            return Resultado.FALHA_SOFT;
        }
    }

    private Boolean conexaoNoInventario(String nome) {
        for (Map<String, Object> instancia : uazapiClient.fetchInstancesRaw()) {
            if (!nome.equals(String.valueOf(instancia.get("name")))) continue;
            Object conectado = instancia.get("isConnected");
            Object autenticado = instancia.get("isLogged");
            if (conectado instanceof Boolean || autenticado instanceof Boolean) {
                if (Boolean.TRUE.equals(conectado) && Boolean.TRUE.equals(autenticado)) return true;
                if (Boolean.FALSE.equals(conectado) && Boolean.FALSE.equals(autenticado)) return false;
                return null;
            }
            Object status = instancia.get("status");
            if (status == null) status = instancia.get("state");
            if (status == null) return null;
            String valor = status.toString().toLowerCase(java.util.Locale.ROOT);
            if (valor.equals("connected") || valor.equals("open")) return true;
            if (valor.equals("disconnected") || valor.equals("close") || valor.equals("closed")) return false;
            return null;
        }
        return null;
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
