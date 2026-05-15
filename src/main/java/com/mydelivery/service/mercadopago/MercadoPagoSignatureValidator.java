package com.mydelivery.service.mercadopago;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Validação HMAC-SHA256 da assinatura do webhook do Mercado Pago.
 *
 * Doc oficial: https://www.mercadopago.com.br/developers/pt/docs/your-integrations/notifications/webhooks
 *
 * MP envia dois headers:
 *   x-signature:  "ts=1700000000,v1=abcd1234..."
 *   x-request-id: identificador único do evento
 *
 * O template assinado é:
 *   "id:<dataId>;request-id:<xRequestId>;ts:<ts>;"
 *
 * Onde dataId é o ID do recurso no payload (params data.id da URL ou body.data.id).
 * Validamos com o segredo configurado pelo restaurante (mpWebhookSecret).
 */
@Slf4j
@Component
public class MercadoPagoSignatureValidator {

    /**
     * @return true se a assinatura confere com o segredo do tenant.
     *         false em qualquer falha (header ausente, formato inválido, mismatch).
     *         NUNCA lança — webhook handler trata false como 401.
     */
    public boolean valido(String xSignatureHeader, String xRequestId, String dataId, String secret) {
        if (secret == null || secret.isBlank()) {
            log.warn("Webhook MP recebido mas restaurante não tem webhook secret configurado");
            return false;
        }
        if (xSignatureHeader == null || xRequestId == null || dataId == null) {
            log.warn("Webhook MP sem headers obrigatórios (signature/requestId/dataId)");
            return false;
        }

        // Parse "ts=...,v1=..."
        String ts = null, v1 = null;
        for (String parte : xSignatureHeader.split(",")) {
            String[] kv = parte.trim().split("=", 2);
            if (kv.length != 2) continue;
            if ("ts".equals(kv[0])) ts = kv[1];
            else if ("v1".equals(kv[0])) v1 = kv[1];
        }
        if (ts == null || v1 == null) {
            log.warn("x-signature mal formado: {}", xSignatureHeader);
            return false;
        }

        // Template exato exigido pelo MP — qualquer divergência (espaço, ordem) invalida.
        String template = "id:" + dataId + ";request-id:" + xRequestId + ";ts:" + ts + ";";

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(template.getBytes(StandardCharsets.UTF_8));
            String esperado = bytesToHex(hash);

            // MessageDigest.isEqual evita timing attacks (comparação constant-time).
            return MessageDigest.isEqual(
                    esperado.getBytes(StandardCharsets.UTF_8),
                    v1.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Erro calculando HMAC do webhook MP", e);
            return false;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
