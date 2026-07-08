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
     *
     * Jul/2026: cresceu de 4 → 12 threads. Motivo: com 100+ restaurantes em
     * horário de pico, 4 threads viravam gargalo. Se 1 instância travava no
     * outbound (Evolution timeout ~4s), 4 tarefas ficavam presas e o BACKLOG
     * de outros restaurantes se acumulava — o Tomcat continua respondendo 200
     * rápido, mas o processamento efetivo do bot só rodava minutos depois. As
     * threads são I/O-bound (aguardam Evolution), então 12 workers custam
     * ~zero de CPU e resolvem o head-of-line.
     */
    private static final java.util.concurrent.ExecutorService BOT_EXEC =
            java.util.concurrent.Executors.newFixedThreadPool(
                    12,
                    r -> {
                        Thread t = new Thread(r, "wa-bot-worker");
                        t.setDaemon(true);
                        return t;
                    });

    /**
     * Cache de dedup por messageId. Guarda por 5min ids já processados —
     * qualquer webhook duplicado (retry da Evolution, replay de reconexão)
     * cai aqui e é descartado ANTES de virar task no BOT_EXEC.
     *
     * Chave: instanceName + ":" + messageId (namespace por instância pra evitar
     * colisão em restaurantes distintos gerarem ids semelhantes).
     * Valor: Boolean.TRUE (só serve pra sinalizar presença).
     *
     * Caffeine expira automático em 5min, sem thread de limpeza — barato de
     * memória mesmo com pico de tráfego. `putIfAbsent` é atômico → sem race.
     *
     * Por que 5min: Evolution retenta webhook em ate ~1min. Reconexão de
     * Baileys pode fazer replay de mensagens até uns 3min atrás. 5min cobre
     * ambos com margem sem ocupar muita memória (~5-10k entries em pico é
     * <1MB).
     */
    private static final com.github.benmanes.caffeine.cache.Cache<String, Boolean> IDS_JA_VISTOS =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(java.time.Duration.ofMinutes(5))
                    .maximumSize(50_000) // hard cap defensivo
                    .build();

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
        String messageId = null;
        if (key instanceof Map<?, ?> k) {
            Map<String, Object> kMap = (Map<String, Object>) k;
            fromMe = Boolean.TRUE.equals(kMap.get("fromMe"));
            Object rj = kMap.get("remoteJid");
            if (rj != null) remoteJid = rj.toString();
            Object idObj = kMap.get("id");
            if (idObj != null) messageId = idObj.toString();
        }
        if (fromMe) return; // Não processa mensagens que NÓS enviamos
        if (remoteJid == null) return;

        // ── DEDUP POR messageId ──────────────────────────────────────────
        // Barreira PRIMÁRIA contra resposta duplicada. Antes o único guard
        // era o throttle temporal de 4s no bot service, que NÃO cobria:
        //   (a) retry HTTP da Evolution (ela retenta o webhook se nosso 200
        //       demorar >~5s por Tomcat overload/GC pause) → mesmo messageId
        //       chega de novo depois do throttle expirar
        //   (b) replay de backlog na reconexão do Baileys — Evolution manda
        //       eventos antigos que o WhatsApp reentregou pós-reconnect
        //   (c) webhook DUPLICADO gerado pela Evolution em situações que
        //       reportamos pra eles (bug conhecido em algumas versões 2.x)
        //
        // Idempotência forte: primeiro request com o id passa; qualquer
        // request seguinte com o MESMO id é descartado silenciosamente por
        // 5min. Sem falso-negativo em produção (dois clientes diferentes
        // gerariam messageIds diferentes; Baileys usa hash único).
        if (messageId != null) {
            String chaveDedup = inst.getInstanceName() + ":" + messageId;
            Boolean visto = IDS_JA_VISTOS.getIfPresent(chaveDedup);
            if (visto != null) {
                log.info("[WA-Webhook][DEDUP] mensagem {}*** ja processada — descartando webhook duplicado (inst={})",
                        messageId.length() > 10 ? messageId.substring(0, 10) : messageId,
                        inst.getInstanceName());
                return;
            }
            IDS_JA_VISTOS.put(chaveDedup, Boolean.TRUE);
        }

        // TRACKER DE REPLY-RATE (Jul/2026 v2): registra que este número respondeu.
        // Se estávamos "esperando resposta" (msg enviada por nós <48h atrás), essa
        // conversa vira "answered" no contador. WhatsApp usa unanswered-message
        // ratio como sinal de ban desde 2026.
        com.mydelivery.service.whatsapp.WhatsappService.trackerResposta(
                inst.getInstanceName(), remoteJid);

        // ── ANTI-BACKLOG / ANTI-SPAM ──────────────────────────────────────
        // Quando Evolution/Baileys reconecta após queda, o WhatsApp drena
        // todas as mensagens acumuladas DURANTE a queda — pode vir uma
        // rajada de dezenas de msgs antigas. Se o bot responder cada uma,
        // o cliente recebe uma rajada de respostas de coisas que mandou
        // HORAS atrás. Resultado: confusão + cliente percebe que é bot +
        // WhatsApp marca como spam → contribui pra shadow ban.
        //
        // Regra: msg com timestamp mais antigo que MAX_MSG_AGE_MIN é
        // descartada SILENCIOSAMENTE — heartbeat e marcar-como-lida já
        // rodaram acima, mas a resposta automática NÃO sai.
        //
        // Evolution envia messageTimestamp em SEGUNDOS (Unix epoch).
        // 3min é seguro: cliente humano normal espera no MÁXIMO ~1min,
        // depois desiste/repete a pergunta. Bot responder 3min+ atrasado
        // é sempre ruim.
        final int MAX_MSG_AGE_SEG = 3 * 60;
        Object tsRaw = dataMap.get("messageTimestamp");
        long tsSegundos = 0;
        if (tsRaw instanceof Number n) tsSegundos = n.longValue();
        else if (tsRaw != null) {
            try { tsSegundos = Long.parseLong(tsRaw.toString()); } catch (Exception ignore) {}
        }
        if (tsSegundos > 0) {
            long agoraSeg = System.currentTimeMillis() / 1000L;
            long idadeSeg = agoraSeg - tsSegundos;
            if (idadeSeg > MAX_MSG_AGE_SEG) {
                log.info("[WA-Webhook] msg de backlog ignorada — idade={}s (>{}s) inst={} de={}***",
                        idadeSeg, MAX_MSG_AGE_SEG, inst.getInstanceName(),
                        remoteJid.length() > 5 ? remoteJid.substring(0, 5) : remoteJid);
                return;
            }
        }

        // HEARTBEAT FORTE — passou de fromMe + remoteJid válido, é mensagem real
        // de cliente. Só agora atualizamos o sinal que prova bot operacional.
        // (O heartbeat fraco já foi atualizado no entry point do webhook.)
        whatsappService.marcarMensagemClienteRecebida(inst);

        // ── ANTI-BOT: marca como lida com delay aleatório 0.8-3s ──
        // Bot que marca tudo como lido instantâneo é fingerprint forte.
        // Humano demora 1-3s pra abrir conversa, ler e responder.
        // Async + fail-safe — não bloqueia o bot e ignora se Evolution
        // não suportar o endpoint.
        if (messageId != null) {
            final String midFinal = messageId;
            final String jidFinal = remoteJid;
            BOT_EXEC.submit(() -> {
                try {
                    Thread.sleep(com.mydelivery.service.whatsapp.BotVariations.randomReadDelayMs());
                    whatsappService.marcarMsgComoLida(inst, jidFinal, midFinal);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) { /* best-effort */ }
            });
        }

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
        //
        // Correlation ID: 8 chars derivados do messageId (curto o suficiente
        // pra ler no log, longo o bastante pra ser único num intervalo grande).
        // Todos os logs abaixo dessa chamada — bot service, whatsapp service,
        // evolution client — herdam esse "wh" no MDC. `grep 'wh=abc12345'` no
        // Railway devolve o ciclo completo daquela mensagem específica.
        final String remoteJidFinal = remoteJid;
        final String corrId = messageId != null && messageId.length() >= 8
                ? messageId.substring(0, 8)
                : String.format("%08x", System.nanoTime() & 0xFFFFFFFFL);
        BOT_EXEC.submit(() -> {
            try {
                org.slf4j.MDC.put("wh", corrId);
                botService.processar(inst, remoteJidFinal, texto);
            } catch (Exception e) {
                log.error("[WA-Webhook] erro assíncrono processando msg: {}", e.getMessage(), e);
            } finally {
                org.slf4j.MDC.remove("wh");
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
