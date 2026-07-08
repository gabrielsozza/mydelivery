package com.mydelivery.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.service.ifood.IfoodService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Webhook receiver do iFood.
 *
 * URL a colocar no portal developer.ifood.com.br → App MyDelivery → Webhook:
 *   {@code https://api.mydeliveryfood.com.br/api/webhooks/ifood}
 *
 * iFood dispara eventos aqui (PLC, CFM, RPR, DSP, CON, CAN, etc). O payload
 * é um ARRAY de eventos — não um único objeto. Cada elemento tem pelo menos
 * {@code orderId}, {@code merchantId}, {@code code}.
 *
 * Assinatura HMAC:
 *   iFood inclui o header {@code X-Ifood-Signature} com HMAC-SHA256 do body
 *   assinado com o clientSecret. Validamos se {@code IFOOD_WEBHOOK_STRICT}
 *   está true — em modo permissivo (default), só logamos warning se não
 *   bater e processamos mesmo assim (evita rejeitar eventos em ambientes
 *   sem clientSecret configurado ainda).
 *
 * Resposta:
 *   Devolve 200 SEMPRE, independente de sucesso interno, pra evitar retry
 *   agressivo do iFood. Erro de processamento fica só no log/incidente e
 *   o próprio polling pega o mesmo pedido depois (defesa em profundidade).
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks/ifood")
@RequiredArgsConstructor
public class IfoodWebhookController {

    private final IfoodService ifoodService;

    @Value("${mydelivery.ifood.client-secret:${IFOOD_CLIENT_SECRET:}}")
    private String clientSecret;

    /** Se true, rejeita webhook com assinatura inválida (403). */
    @Value("${mydelivery.ifood.webhook-strict:${IFOOD_WEBHOOK_STRICT:false}}")
    private boolean strictSignature;

    /**
     * Aceita array OU objeto único. Alguns fluxos do iFood mandam
     * {@code [{...}, {...}]}, outros mandam {@code {...}} — normalizamos.
     */
    @PostMapping
    public ResponseEntity<Void> receber(
            @RequestBody(required = false) Object body,
            @RequestHeader(value = "X-Ifood-Signature", required = false) String signature,
            @RequestHeader(value = "x-ifood-signature", required = false) String signatureLower) {

        if (body == null) {
            log.debug("[iFood-Webhook] sem body");
            return ResponseEntity.ok().build();
        }

        // Header case-insensitive
        String sig = signature != null ? signature : signatureLower;

        // Validação HMAC (best-effort ou strict via env var).
        // Idealmente validaríamos contra o RAW body, mas o Spring já parseou.
        // Reserializamos JSON de forma canônica pra comparar. Se strictSignature
        // ligado, rejeita quando não bate; senão só loga.
        if (clientSecret != null && !clientSecret.isBlank() && sig != null) {
            try {
                String reserializado = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(body);
                String esperado = assinarHmacSha256(reserializado, clientSecret);
                if (!esperado.equalsIgnoreCase(sig.trim())) {
                    log.warn("[iFood-Webhook] assinatura invalida (esperado={} recebido={})",
                            esperado, sig);
                    if (strictSignature) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                    }
                }
            } catch (Exception e) {
                log.warn("[iFood-Webhook] falha validando assinatura: {}", e.getMessage());
            }
        }

        // Normaliza pra lista
        List<Object> eventos;
        if (body instanceof List<?> lst) {
            eventos = new java.util.ArrayList<>(lst);
        } else {
            eventos = List.of(body);
        }

        int ok = 0, fail = 0;
        for (Object ev : eventos) {
            if (!(ev instanceof Map<?, ?> m)) { fail++; continue; }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> evMap = (Map<String, Object>) m;
                if (ifoodService.processarEvento(evMap)) ok++; else fail++;
            } catch (Exception e) {
                fail++;
                log.error("[iFood-Webhook] erro processando evento: {}", e.getMessage(), e);
            }
        }
        log.info("[iFood-Webhook] recebidos={} ok={} fail={}", eventos.size(), ok, fail);

        // Sempre 200 — mesmo se falhou, polling pega depois. Evita retry
        // agressivo do iFood que gera pedidos duplicados.
        return ResponseEntity.ok().build();
    }

    private static String assinarHmacSha256(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
