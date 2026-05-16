package com.mydelivery.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.config.WhatsappCloudProperties;
import com.mydelivery.service.whatsapp.WhatsappCloudClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Recebe webhooks da Meta WhatsApp Cloud API.
 *
 * Meta usa 2 endpoints no MESMO path:
 *  - GET /api/webhooks/whatsapp-cloud → verificação inicial (responde hub.challenge)
 *  - POST /api/webhooks/whatsapp-cloud → eventos (mensagens recebidas, status)
 *
 * Payload das mensagens (POST):
 * {
 *   "object": "whatsapp_business_account",
 *   "entry": [{
 *     "id": "<WABA_ID>",
 *     "changes": [{
 *       "field": "messages",
 *       "value": {
 *         "messaging_product": "whatsapp",
 *         "metadata": {...},
 *         "contacts": [...],
 *         "messages": [{
 *           "from": "5511...",
 *           "id": "wamid...",
 *           "timestamp": "...",
 *           "type": "text",
 *           "text": {"body": "Oi"}
 *         }]
 *       }
 *     }]
 *   }]
 * }
 *
 * Segurança em produção: validar assinatura HMAC-SHA256 com App Secret (header
 * x-hub-signature-256). Por enquanto endpoint é aberto pra simplificar testes.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks/whatsapp-cloud")
@RequiredArgsConstructor
public class WhatsappCloudWebhookController {

    private final WhatsappCloudProperties props;
    private final WhatsappCloudClient cloudClient;

    /**
     * Verificação inicial do webhook. Meta envia GET com 3 params:
     *   hub.mode=subscribe, hub.verify_token=<nosso>, hub.challenge=<random>
     * Se token bater, devolvemos o challenge em texto puro.
     */
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {

        log.info("[WA-Cloud] verify recebido mode={} tokenMatch={}",
                mode, token != null && token.equals(props.getVerifyToken()));

        if ("subscribe".equals(mode)
                && props.getVerifyToken() != null
                && props.getVerifyToken().equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).body("Verification failed");
    }

    /**
     * Recebe eventos. Por enquanto: extrai texto da mensagem e responde
     * automaticamente. Próxima iteração: rotear pro WhatsappBotService.
     */
    @PostMapping
    public ResponseEntity<Void> receber(@RequestBody Map<String, Object> payload) {
        log.info("[WA-Cloud] evento recebido: keys={}", payload.keySet());

        try {
            processarPayload(payload);
        } catch (Exception e) {
            log.error("[WA-Cloud] erro processando webhook: {}", e.getMessage(), e);
        }
        // Sempre devolve 200 — se devolver erro, Meta vai reentregar várias vezes
        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private void processarPayload(Map<String, Object> payload) {
        Object entryObj = payload.get("entry");
        if (!(entryObj instanceof List<?> entries)) return;

        for (Object entryItem : entries) {
            if (!(entryItem instanceof Map<?, ?> entryMap)) continue;
            Object changesObj = entryMap.get("changes");
            if (!(changesObj instanceof List<?> changes)) continue;

            for (Object changeItem : changes) {
                if (!(changeItem instanceof Map<?, ?> changeMap)) continue;
                Object valueObj = changeMap.get("value");
                if (!(valueObj instanceof Map<?, ?> value)) continue;

                Object messagesObj = ((Map<String, Object>) value).get("messages");
                if (!(messagesObj instanceof List<?> messages)) continue;

                for (Object msgItem : messages) {
                    if (!(msgItem instanceof Map<?, ?> msgMap)) continue;
                    processarMensagem((Map<String, Object>) msgMap);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void processarMensagem(Map<String, Object> msg) {
        String from = strDe(msg, "from");
        String type = strDe(msg, "type");
        if (from == null) return;

        String texto = null;
        if ("text".equals(type)) {
            Object textObj = msg.get("text");
            if (textObj instanceof Map<?, ?> textMap) {
                texto = strDe((Map<String, Object>) textMap, "body");
            }
        }

        log.info("[WA-Cloud] msg de {} type={} texto={}",
                from, type, texto == null ? "(não texto)" : "\"" + texto + "\"");

        if (texto == null || texto.isBlank()) {
            // Tipos não-texto (image, audio, location, etc) — ignora por enquanto
            return;
        }

        // ECO de teste — prova que recebimento + envio funcionam end-to-end.
        // Próxima iteração: chamar WhatsappBotService.processar(...) pra resposta real.
        String resposta = "Olá! Recebi: \"" + texto + "\". MyDelivery em breve responde com o cardápio.";
        try {
            cloudClient.enviarTexto(from, resposta);
        } catch (Exception e) {
            log.error("[WA-Cloud] falha ao responder {}: {}", from, e.getMessage());
        }
    }

    private String strDe(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
}
