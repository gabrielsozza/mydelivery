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
    private final com.mydelivery.repository.WhatsappInstanceRepository instanceRepo;
    private final com.mydelivery.service.whatsapp.UazapiClient uazapiClient;

    /**
     * Cache número WhatsApp → nome da instância. Uazapi manda webhooks com
     * {@code owner=5527988387661} (número, não nome). O primeiro hit resolve
     * via BD (findByPhone) ou {@code /instance/all}; próximos batem no cache.
     * Sem esse cache cada mensagem gastava uma chamada HTTP pro Uazapi.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> numeroParaNome =
            new java.util.concurrent.ConcurrentHashMap<>();

    @PostMapping("/api/webhooks/whatsapp/uazapi")
    public ResponseEntity<Void> receber(@RequestBody(required = false) Map<String, Object> payload) {
        if (payload == null) {
            log.debug("[Uazapi-Webhook] sem body");
            return ResponseEntity.ok().build();
        }

        // Log DIAGNÓSTICO — payload cru pra descobrir o formato real
        // (o OpenAPI da Uazapi diverge do que eles enviam na prática).
        // Dump top-level keys + valores curtos + chaves aninhadas de "data",
        // "message", "connection" pra achar onde vem o instance name e o texto.
        log.info("[Uazapi-Webhook] === RAW payload ===");
        for (var entry : payload.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof Map<?, ?> m) {
                log.info("[Uazapi-Webhook]   {} = (Map) keys={}", entry.getKey(), m.keySet());
            } else if (v instanceof java.util.List<?> l) {
                log.info("[Uazapi-Webhook]   {} = (List size={})", entry.getKey(), l.size());
            } else {
                String vs = v == null ? "null" : v.toString();
                if (vs.length() > 120) vs = vs.substring(0, 117) + "...";
                log.info("[Uazapi-Webhook]   {} = {}", entry.getKey(), vs);
            }
        }
        log.info("[Uazapi-Webhook] === END raw ===");

        String eventUazapi = strDe(payload, "EventType");
        if (eventUazapi == null) eventUazapi = strDe(payload, "event");
        if (eventUazapi == null) eventUazapi = strDe(payload, "type");
        String instanceName = extrairInstanceName(payload);
        if (instanceName == null || instanceName.isBlank()) {
            log.warn("[Uazapi-Webhook] payload sem instance name — descartando. event={} keys={}",
                    eventUazapi, payload.keySet());
            return ResponseEntity.ok().build();
        }

        // ── RESOLVE NÚMERO → NOME ─────────────────────────────────────────
        // Uazapi manda owner=5527988387661 (número do WhatsApp conectado)
        // em vez do nome da instância. Antes desse resolver, o handler
        // buscava por nome, não achava, descartava a mensagem — bot mudo.
        // Resolução em 3 camadas: cache → BD (findByPhone) → /instance/all.
        // Assim que descobrir o nome, PERSISTE o phone no BD pra próximos
        // webhooks baterem direto por findByPhone (sem chamada HTTP).
        if (instanceName.matches("\\d{10,15}")) {
            String numero = instanceName;
            String nomeReal = numeroParaNome.get(numero);
            if (nomeReal == null) {
                nomeReal = resolverNomePeloNumero(numero);
                if (nomeReal != null) {
                    numeroParaNome.put(numero, nomeReal);
                    log.info("[Uazapi-Webhook] resolvido número {} → instância '{}'", numero, nomeReal);
                } else {
                    log.warn("[Uazapi-Webhook] não consegui resolver número {} pra nome de instância — descartando", numero);
                    return ResponseEntity.ok().build();
                }
            }
            instanceName = nomeReal;
        }

        log.info("[Uazapi-Webhook] event={} instance={} → traduzindo",
                eventUazapi, instanceName);

        // TRADUZ pro formato Evolution e delega pro handler central.
        Map<String, Object> evoPayload = traduzir(eventUazapi, payload);
        if (evoPayload == null) {
            log.info("[Uazapi-Webhook] event={} sem tradução conhecida — ignorado", eventUazapi);
            return ResponseEntity.ok().build();
        }
        log.info("[Uazapi-Webhook] delegando ao handler Evolution — evoEvent={}", evoPayload.get("event"));
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
        String[] chaves = {
                "owner", "instance", "instance_name", "instanceName",
                "instanceId", "instance_id", "name", "token"
        };
        for (String k : chaves) {
            Object v = payload.get(k);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        // Talvez dentro de data / message / instance (aninhado)
        String[] wrappers = { "data", "message", "instance" };
        for (String w : wrappers) {
            Object d = payload.get(w);
            if (d instanceof Map<?, ?> dm) {
                Map<String, Object> dMap = (Map<String, Object>) dm;
                for (String k : chaves) {
                    Object v = dMap.get(k);
                    if (v != null && !v.toString().isBlank()) return v.toString();
                }
            }
        }
        return null;
    }

    private String strDe(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    /**
     * Resolve o NÚMERO WhatsApp ({@code 5527988387661}) pro nome da instância
     * ({@code mydelivery-rest-21}). Estratégia em cascata:
     *
     * <ol>
     *   <li><b>BD</b>: {@code findByPhone} — se algum boot anterior já capturou
     *       o número via Uazapi ou webhook connection, tá salvo aqui.</li>
     *   <li><b>Uazapi</b>: {@code /instance/all} — varre todas as instâncias
     *       e procura pela que tem {@code jid.user}/{@code owner}/{@code wuid}
     *       batendo o número. Quando acha, PERSISTE o phone no BD pra próximos
     *       webhooks resolverem direto pelo passo 1.</li>
     * </ol>
     *
     * Retorna {@code null} se nenhuma camada resolveu (webhook fica descartado
     * — não tem como saber pra qual restaurante entregar).
     */
    private String resolverNomePeloNumero(String numero) {
        // Camada 1: BD (fast path)
        try {
            var opt = instanceRepo.findByPhone(numero);
            if (opt.isPresent()) return opt.get().getInstanceName();
        } catch (Exception e) {
            log.warn("[Uazapi-Webhook] findByPhone falhou: {}", e.getMessage());
        }
        // Camada 2: /instance/all + persiste phone no BD
        try {
            var todas = uazapiClient.fetchInstancesRaw();
            for (var u : todas) {
                String numeroUazapi = extrairNumeroDaInstancia(u);
                if (numeroUazapi == null || !numeroUazapi.equals(numero)) continue;
                Object name = u.get("name");
                if (name == null) continue;
                String nome = name.toString();

                // Persiste phone no BD (best-effort — se falhar cache in-memory
                // ainda evita re-chamada)
                try {
                    instanceRepo.findByInstanceName(nome).ifPresent(inst -> {
                        if (inst.getPhone() == null || !numero.equals(inst.getPhone())) {
                            inst.setPhone(numero);
                            instanceRepo.save(inst);
                            log.info("[Uazapi-Webhook] phone {} persistido no BD pra {}", numero, nome);
                        }
                    });
                } catch (Exception e) {
                    log.warn("[Uazapi-Webhook] erro salvando phone no BD: {}", e.getMessage());
                }
                return nome;
            }
            log.warn("[Uazapi-Webhook] /instance/all tem {} instâncias mas nenhuma bate número {}",
                    todas.size(), numero);
        } catch (Exception e) {
            log.warn("[Uazapi-Webhook] fetchInstancesRaw falhou: {}", e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extrairNumeroDaInstancia(Map<String, Object> u) {
        Object jid = u.get("jid");
        if (jid instanceof Map<?, ?> jm) {
            Object user = ((Map<String, Object>) jm).get("user");
            if (user != null) {
                String s = user.toString();
                if (s.matches("\\d+")) return s;
            }
        }
        for (String k : new String[]{ "owner", "wuid", "number", "phone", "whatsappNumber" }) {
            Object v = u.get(k);
            if (v == null) continue;
            String s = v.toString().replaceAll("[^0-9]", "");
            if (s.length() >= 10 && s.length() <= 15) return s;
        }
        return null;
    }
}
