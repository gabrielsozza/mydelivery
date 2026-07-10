package com.mydelivery.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Recebe eventos da Uazapi (webhook global do servidor).
 *
 * <p>Uazapi manda TODOS os eventos de TODAS as instâncias pra um único endpoint
 * global (configurado no painel deles em "Webhook Global"). O payload traz
 * qual instância originou ({@code data.owner} ou {@code instance} no root).
 *
 * <p>Estratégia: <b>traduzir</b> o payload Uazapi pro shape esperado pelo
 * {@link WhatsappWebhookController} (formato Evolution) e delegar — assim o
 * fluxo grande (dedup, retry, bot dispatcher, backpressure) permanece um só.
 *
 * <p>Formato Uazapi típico ({@code messages}):
 * <pre>
 * {
 *   "EventType": "messages",
 *   "instance": "&lt;instance_name&gt;",
 *   "owner": "&lt;instance_name&gt;",
 *   "message": {
 *     "id": "&lt;uuid_interno&gt;",
 *     "messageid": "&lt;wa_id&gt;",
 *     "chatid": "5511999...@s.whatsapp.net",
 *     "sender": "5511999...@s.whatsapp.net",
 *     "senderName": "João",
 *     "fromMe": false,
 *     "isGroup": false,
 *     "text": "quero pedir",
 *     "messageType": "conversation",
 *     "messageTimestamp": 1750000000
 *   }
 * }
 * </pre>
 *
 * <p>Formato Uazapi de conexão/QR ({@code connection}): campo {@code qrcode}
 * (base64 sem prefixo) e {@code isLogged}/{@code isConnected}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class UazapiWebhookController {

    private final WhatsappWebhookController evolutionHandler;

    @PostMapping("/api/webhooks/whatsapp/uazapi")
    public ResponseEntity<Void> receber(@RequestBody(required = false) Map<String, Object> payload) {
        if (payload == null) {
            log.debug("[Uazapi-Webhook] sem body");
            return ResponseEntity.ok().build();
        }

        String eventUazapi = strDe(payload, "EventType");
        if (eventUazapi == null) eventUazapi = strDe(payload, "event");
        String instanceName = extrairInstanceName(payload);
        if (instanceName == null || instanceName.isBlank()) {
            log.warn("[Uazapi-Webhook] payload sem instance name — descartando. keys={}", payload.keySet());
            return ResponseEntity.ok().build();
        }

        // TRADUZ pro formato Evolution e delega pro handler central.
        Map<String, Object> evoPayload = traduzir(eventUazapi, payload);
        if (evoPayload == null) {
            // Evento sem tradução conhecida (ex: presence) — ignora.
            return ResponseEntity.ok().build();
        }
        return evolutionHandler.processarWebhookEvolution(instanceName, evoPayload);
    }

    /**
     * Traduz payload Uazapi pro formato Evolution:
     * <ul>
     *   <li>Uazapi {@code messages}      → Evolution {@code MESSAGES_UPSERT}</li>
     *   <li>Uazapi {@code connection}    → Evolution {@code CONNECTION_UPDATE}
     *       ou {@code QRCODE_UPDATED} (se veio QR)</li>
     * </ul>
     * Retorna {@code null} se o evento não interessa.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> traduzir(String eventoUazapi, Map<String, Object> src) {
        if (eventoUazapi == null) return null;
        String e = eventoUazapi.toLowerCase();

        // ── MESSAGES ─────────────────────────────────────────────────────
        if (e.contains("message")) {
            Map<String, Object> msg = extrairMessage(src);
            if (msg == null) return null;

            // key: { remoteJid, fromMe, id }
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("remoteJid", strDe(msg, "chatid"));
            key.put("fromMe", Boolean.TRUE.equals(msg.get("fromMe")));
            String messageId = strDe(msg, "messageid");
            if (messageId == null) messageId = strDe(msg, "id");
            key.put("id", messageId);

            // message: { conversation: "..." } — WhatsappWebhookController.extrairTexto
            Map<String, Object> message = new LinkedHashMap<>();
            String texto = strDe(msg, "text");
            if (texto == null) texto = strDe(msg, "content");
            if (texto != null && !texto.isBlank()) {
                message.put("conversation", texto);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("key", key);
            data.put("message", message);
            Object ts = msg.get("messageTimestamp");
            if (ts instanceof Number n) {
                // Uazapi manda em MILISSEGUNDOS (spec). Evolution espera SEGUNDOS
                // no messageTimestamp. Converte pra evitar msg legítima ser
                // marcada como "de backlog" (>3min) pelo anti-spam.
                long v = n.longValue();
                if (v > 10_000_000_000L) v /= 1000L; // heurística: se > ano 2286, é ms
                data.put("messageTimestamp", v);
            }
            data.put("pushName", strDe(msg, "senderName"));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("event", "MESSAGES_UPSERT");
            out.put("data", data);
            return out;
        }

        // ── CONNECTION ───────────────────────────────────────────────────
        if (e.contains("connection")) {
            Object connData = src.get("connection");
            Map<String, Object> conn = connData instanceof Map<?, ?>
                    ? (Map<String, Object>) connData : src;

            // Uazapi pode mandar QR aqui — vira QRCODE_UPDATED.
            Object qrcode = conn.get("qrcode");
            if (qrcode == null) qrcode = src.get("qrcode");
            if (qrcode != null && !qrcode.toString().isBlank()) {
                Map<String, Object> data = new LinkedHashMap<>();
                Map<String, Object> qrWrap = new LinkedHashMap<>();
                qrWrap.put("base64", qrcode.toString());
                data.put("qrcode", qrWrap);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("event", "QRCODE_UPDATED");
                out.put("data", data);
                return out;
            }

            boolean connected = Boolean.TRUE.equals(conn.get("isConnected"))
                    || Boolean.TRUE.equals(conn.get("connected"));
            boolean loggedIn = Boolean.TRUE.equals(conn.get("isLogged"))
                    || Boolean.TRUE.equals(conn.get("loggedIn"));
            String state;
            if (connected && loggedIn) state = "open";
            else if (connected || loggedIn) state = "connecting";
            else state = "close";

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("state", state);
            // Se veio número (jid), passa como wuid pra ficar igual Evolution.
            Object jid = conn.get("jid");
            if (jid instanceof Map<?, ?> jm) {
                Object user = ((Map<String, Object>) jm).get("user");
                if (user != null) data.put("wuid", user.toString());
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("event", "CONNECTION_UPDATE");
            out.put("data", data);
            return out;
        }

        // presence / groups / etc — ignora
        return null;
    }

    /** Uazapi pode aninhar o message em vários lugares — busca todos. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extrairMessage(Map<String, Object> src) {
        Object m = src.get("message");
        if (m instanceof Map<?, ?> mm) return (Map<String, Object>) mm;
        Object d = src.get("data");
        if (d instanceof Map<?, ?> dm) {
            Map<String, Object> dMap = (Map<String, Object>) dm;
            Object m2 = dMap.get("message");
            if (m2 instanceof Map<?, ?> m2m) return (Map<String, Object>) m2m;
            // Talvez data JÁ SEJA o message
            if (dMap.get("chatid") != null || dMap.get("text") != null) return dMap;
        }
        // Root pode ser o próprio message
        if (src.get("chatid") != null || src.get("text") != null) return src;
        return null;
    }

    /**
     * Uazapi pode indicar instância em vários lugares:
     *  - {@code owner} (mais comum)
     *  - {@code instance} (nome ou id)
     *  - {@code instance_name}
     * O que a gente precisa aqui é o NOME da instância — Uazapi usa o mesmo
     * "name" que passamos em {@code /instance/create}, ou seja, o slug do
     * restaurante ou o nome que o WhatsappService usou.
     */
    @SuppressWarnings("unchecked")
    private String extrairInstanceName(Map<String, Object> payload) {
        String[] chaves = { "owner", "instance", "instance_name", "instanceName", "name" };
        for (String k : chaves) {
            Object v = payload.get(k);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        // Talvez dentro de data.
        Object d = payload.get("data");
        if (d instanceof Map<?, ?> dm) {
            Map<String, Object> dMap = (Map<String, Object>) dm;
            for (String k : chaves) {
                Object v = dMap.get(k);
                if (v != null && !v.toString().isBlank()) return v.toString();
            }
        }
        return null;
    }

    private String strDe(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
}
