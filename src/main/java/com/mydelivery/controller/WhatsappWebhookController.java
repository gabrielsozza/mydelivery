package com.mydelivery.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.service.whatsapp.WhatsappBotService;
import com.mydelivery.service.whatsapp.WhatsappService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Recebe eventos da Evolution API.
 *
 * Eventos tratados:
 *  - MESSAGES_UPSERT   → mensagem recebida; passa pro WhatsappBotService.
 *  - CONNECTION_UPDATE → state "open"/"close" muda status local.
 *  - QRCODE_UPDATED    → QR base64 vem aqui na Evolution v2.1.x; salva pro frontend ler.
 *
 * Segurança: endpoint público porque Evolution não tem como apresentar JWT.
 * O caminho inclui {instanceName} — só processamos se acharmos uma instância
 * local com esse nome. Para hardening em prod, considerar IP allowlist da Evolution.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WhatsappWebhookController {

    private final WhatsappService whatsappService;
    private final WhatsappBotService botService;

    /**
     * Pool dedicado pra processamento assíncrono de mensagens recebidas.
     * Antes: tratarMensagem era SÍNCRONO — webhook só devolvia 200 depois que
     * o bot decidia + chamava Evolution outbound (~1-3s). Isso fazia 2 coisas
     * ruins: (a) Evolution podia timeoutar e retentar, gerando msg duplicada;
     * (b) bloqueava o thread do Tomcat por toda a duração.
     * Agora: devolve 200 em <50ms, processamento corre em pool separado.
     * Pool fixo de 4 threads — suficiente pra dezenas de restaurantes,
     * já que processamento real é ~100ms quando warm.
     */
    private static final java.util.concurrent.ExecutorService BOT_EXEC =
            java.util.concurrent.Executors.newFixedThreadPool(
                    4,
                    r -> {
                        Thread t = new Thread(r, "wa-bot-worker");
                        t.setDaemon(true);
                        return t;
                    });

    /**
     * Scheduler pra retry de webhook quando a instância ainda não está no DB
     * (race condition com a transação do /conectar). 2 tentativas: 2s e 6s.
     */
    private static final java.util.concurrent.ScheduledExecutorService WEBHOOK_RETRY_EXEC =
            java.util.concurrent.Executors.newScheduledThreadPool(
                    2,
                    r -> {
                        Thread t = new Thread(r, "wa-webhook-retry");
                        t.setDaemon(true);
                        return t;
                    });

    /** Retry máx por delay em segundos */
    private static final int[] RETRY_DELAYS_SECONDS = { 2, 6 };

    /**
     * Reagenda processamento do webhook após N segundos. Usado quando a
     * instância ainda não está no DB local por causa da race condition com
     * a transação do /conectar. Tenta até 2 vezes (2s e 6s).
     */
    private void agendarRetryWebhook(String instanceName, Map<String, Object> payload, int tentativa) {
        if (tentativa > RETRY_DELAYS_SECONDS.length) {
            log.warn("[WA-Webhook] instância {} continua não encontrada após {} retries — desistindo",
                    instanceName, RETRY_DELAYS_SECONDS.length);
            return;
        }
        int delay = RETRY_DELAYS_SECONDS[tentativa - 1];
        WEBHOOK_RETRY_EXEC.schedule(() -> {
            try {
                WhatsappInstance inst = whatsappService.buscarPorNome(instanceName);
                if (inst == null) {
                    // Ainda não chegou — agenda próximo retry
                    agendarRetryWebhook(instanceName, payload, tentativa + 1);
                    return;
                }
                log.info("[WA-Webhook] instância {} encontrada no retry #{} — processando agora",
                        instanceName, tentativa);
                // Re-executa o handler como se o webhook tivesse acabado de chegar
                whatsappService.marcarMensagemRecebida(inst);
                String event = strDe(payload, "event");
                registrarEvento(instanceName, event, payload);
                switch (event == null ? "" : event) {
                    case "messages.upsert", "MESSAGES_UPSERT" -> tratarMensagem(inst, payload);
                    case "connection.update", "CONNECTION_UPDATE" -> tratarConexao(inst, payload);
                    case "qrcode.updated", "QRCODE_UPDATED" -> tratarQrCode(inst, payload);
                    default -> { /* ignora */ }
                }
            } catch (Exception e) {
                log.error("[WA-Webhook] erro no retry da instância {}: {}", instanceName, e.getMessage(), e);
            }
        }, delay, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Buffer in-memory dos últimos N webhooks recebidos, por instância.
     *  Serve pra diagnóstico ao vivo: "a Evolution está enviando mensagens
     *  ou não?". É memória volátil — perde no restart, mas pra debug serve. */
    private static final int BUFFER_TAM = 30;
    private static final java.util.Map<String, java.util.Deque<java.util.Map<String, Object>>> ULTIMOS_EVENTOS
            = new java.util.concurrent.ConcurrentHashMap<>();

    public static java.util.List<java.util.Map<String, Object>> snapshotEventos(String instanceName) {
        var dq = ULTIMOS_EVENTOS.get(instanceName);
        if (dq == null) return java.util.List.of();
        return new java.util.ArrayList<>(dq);
    }

    private static void registrarEvento(String instanceName, String event, java.util.Map<String, Object> payload) {
        var dq = ULTIMOS_EVENTOS.computeIfAbsent(instanceName,
                k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        java.util.Map<String, Object> reg = new java.util.LinkedHashMap<>();
        reg.put("em", java.time.LocalDateTime.now().toString());
        reg.put("event", event);
        Object data = payload == null ? null : payload.get("data");
        if (data instanceof java.util.Map<?, ?> dm) {
            reg.put("dataKeys", new java.util.ArrayList<>(dm.keySet()));
        }
        dq.addFirst(reg);
        while (dq.size() > BUFFER_TAM) dq.pollLast();
    }

    @PostMapping("/api/webhooks/whatsapp/{instanceName}")
    public ResponseEntity<Void> receber(
            @PathVariable String instanceName,
            @RequestBody(required = false) Map<String, Object> payload) {

        if (payload == null) {
            log.debug("[WA-Webhook] {} sem body", instanceName);
            return ResponseEntity.ok().build();
        }

        WhatsappInstance inst = whatsappService.buscarPorNome(instanceName);
        if (inst == null) {
            // CAUSA RAIZ HISTÓRICA do "QR carrega eternamente":
            // Race condition. O POST /conectar é @Transactional. Quando ele
            // chama Evolution.criarInstancia, a Evolution IMEDIATAMENTE começa
            // a emitir webhook (QRCODE_UPDATED) ANTES da transação local
            // commitar. Webhook chega aqui, buscarPorNome() não encontra
            // (ainda não foi commitado), e descartávamos. Quando o próximo
            // QRCODE_UPDATED chegava ~25s depois, o QR original já tinha
            // expirado no celular do dono.
            //
            // Fix: agenda retry assíncrono em 2s e 6s antes de desistir.
            // 99% dos casos resolvem na 1ª tentativa (transação commita
            // em <500ms). 6s cobre cold start de DB pool.
            log.warn("[WA-Webhook] instância {} não encontrada — agendando retry em 2s+6s", instanceName);
            agendarRetryWebhook(instanceName, payload, 1);
            return ResponseEntity.ok().build();
        }

        // HEARTBEAT REAL — atualiza timestamp em QUALQUER evento que chega da
        // Evolution. É o único sinal forte de que a sessão WhatsApp está viva.
        // Se ficar muito tempo sem update mesmo com status=CONECTADA, é zumbi.
        whatsappService.marcarMensagemRecebida(inst);

        String event = strDe(payload, "event");
        // Log granular por evento: DEBUG (poluiu prod com 100+ restaurantes ativos,
        // cada msg recebida no WhatsApp logava). Eventos relevantes (CONNECTION_UPDATE,
        // QRCODE_UPDATED, erros) sao logados em INFO dentro dos respectivos handlers.
        if (log.isDebugEnabled()) {
            Object dataDbg = payload.get("data");
            log.debug("[WA-Webhook] {} evento={} data-keys={}", instanceName, event,
                    dataDbg instanceof Map<?, ?> mdbg ? mdbg.keySet()
                            : (dataDbg == null ? "null" : dataDbg.getClass().getSimpleName()));
        }
        registrarEvento(instanceName, event, payload);

        try {
            switch (event == null ? "" : event) {
                case "messages.upsert", "MESSAGES_UPSERT" -> tratarMensagem(inst, payload);
                case "connection.update", "CONNECTION_UPDATE" -> tratarConexao(inst, payload);
                case "qrcode.updated", "QRCODE_UPDATED" -> tratarQrCode(inst, payload);
                default -> { /* outros eventos ignorados */ }
            }
        } catch (Exception e) {
            log.error("[WA-Webhook] Erro tratando evento {}: {}", event, e.getMessage(), e);
            // Devolve 200 mesmo assim — Evolution não precisa retry pra erros nossos
        }

        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private void tratarMensagem(WhatsappInstance inst, Map<String, Object> payload) {
        Object data = payload.get("data");
        if (!(data instanceof Map<?, ?> d)) return;
        Map<String, Object> dataMap = (Map<String, Object>) d;

        // Evolution envia "key": { "remoteJid": "...", "fromMe": bool, "id": "..." }
        Object key = dataMap.get("key");
        boolean fromMe = false;
        String remoteJid = null;
        if (key instanceof Map<?, ?> k) {
            Map<String, Object> kMap = (Map<String, Object>) k;
            fromMe = Boolean.TRUE.equals(kMap.get("fromMe"));
            Object rj = kMap.get("remoteJid");
            if (rj != null) remoteJid = rj.toString();
        }
        if (fromMe) return; // Não processa mensagens que NÓS enviamos
        if (remoteJid == null) return;

        // HEARTBEAT FORTE — passou de fromMe + remoteJid válido, é mensagem real
        // de cliente. Só agora atualizamos o sinal que prova bot operacional.
        // (O heartbeat fraco já foi atualizado no entry point do webhook.)
        whatsappService.marcarMensagemClienteRecebida(inst);

        // Texto: pode estar em message.conversation ou message.extendedTextMessage.text
        String texto = extrairTexto(dataMap);
        if (texto == null || texto.isBlank()) return;

        // Ignora grupos (remoteJid termina em @g.us)
        if (remoteJid.endsWith("@g.us")) {
            log.debug("[WA-Webhook] mensagem de grupo ignorada");
            return;
        }

        log.info("[WA-Webhook] msg recebida — instância={}, de={}***, len={}",
                inst.getInstanceName(),
                remoteJid.length() > 5 ? remoteJid.substring(0, 5) : remoteJid,
                texto.length());

        // ASYNC: webhook devolve 200 imediato; bot processa em pool dedicado.
        // Evita timeout do Evolution (que retentava e gerava mensagem duplicada)
        // e desbloqueia o thread do Tomcat.
        // (NÃO passamos pushName — clientes com nome tipo "." ou emojis no
        // perfil ficavam com saudação estranha. Mantemos o bot impessoal.)
        final String remoteJidFinal = remoteJid;
        BOT_EXEC.submit(() -> {
            try {
                botService.processar(inst, remoteJidFinal, texto);
            } catch (Exception e) {
                log.error("[WA-Webhook] erro assíncrono processando msg: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * Evolution v2.1.x envia o QR Code via webhook. Payload típico:
     *   { "event": "qrcode.updated", "data": { "qrcode": { "base64": "data:image/png;base64,..." } } }
     * ou em algumas variantes diretamente:
     *   { "event": "qrcode.updated", "data": { "base64": "..." } }
     */
    @SuppressWarnings("unchecked")
    private void tratarQrCode(WhatsappInstance inst, Map<String, Object> payload) {
        Object data = payload.get("data");
        // TEMP log diagnóstico pra ver estrutura real do payload Evolution v2.1.x
        log.info("[WA-Webhook][QR] data type={} content={}",
                data == null ? "null" : data.getClass().getSimpleName(),
                data instanceof Map<?, ?> m ? m.keySet() : data);
        if (!(data instanceof Map<?, ?> d)) return;
        Map<String, Object> dataMap = (Map<String, Object>) d;

        // Tenta data.qrcode.base64 primeiro (formato v2.1.x)
        String qr = null;
        Object qrcode = dataMap.get("qrcode");
        if (qrcode instanceof Map<?, ?> qm) {
            Object b64 = ((Map<String, Object>) qm).get("base64");
            if (b64 != null) qr = b64.toString();
            if (qr == null) {
                Object code = ((Map<String, Object>) qm).get("code");
                if (code != null) qr = code.toString();
            }
        }
        // Fallback: data.base64 ou data.code (formato antigo)
        if (qr == null) {
            Object b64 = dataMap.get("base64");
            if (b64 != null) qr = b64.toString();
        }
        if (qr == null) {
            Object code = dataMap.get("code");
            if (code != null) qr = code.toString();
        }

        if (qr != null) {
            whatsappService.salvarQrCode(inst, qr);
        } else {
            log.warn("[WA-Webhook] QRCODE_UPDATED chegou sem base64/code: {}", payload.keySet());
        }
    }

    @SuppressWarnings("unchecked")
    private void tratarConexao(WhatsappInstance inst, Map<String, Object> payload) {
        Object data = payload.get("data");
        if (!(data instanceof Map<?, ?> d)) return;
        Map<String, Object> dataMap = (Map<String, Object>) d;
        String state = strDe(dataMap, "state");

        if ("open".equalsIgnoreCase(state)) {
            // Tenta extrair número conectado (pode vir em wuid/owner/me)
            String phone = strDe(dataMap, "wuid");
            if (phone == null) phone = strDe(dataMap, "owner");
            if (phone != null) phone = phone.replaceAll("[^0-9]", "");
            whatsappService.marcarConectada(inst, phone);
        } else if ("close".equalsIgnoreCase(state)) {
            whatsappService.marcarDesconectada(inst);
        }
    }

    @SuppressWarnings("unchecked")
    private String extrairTexto(Map<String, Object> dataMap) {
        Object msg = dataMap.get("message");
        if (!(msg instanceof Map<?, ?> m)) return null;
        Map<String, Object> mMap = (Map<String, Object>) m;
        Object conv = mMap.get("conversation");
        if (conv != null) return conv.toString();
        Object ext = mMap.get("extendedTextMessage");
        if (ext instanceof Map<?, ?> e) {
            Object txt = ((Map<String, Object>) e).get("text");
            if (txt != null) return txt.toString();
        }
        return null;
    }

    private String strDe(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
}
