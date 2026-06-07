package com.mydelivery.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.service.mercadopago.MercadoPagoWebhookService;
import com.mydelivery.service.mercadopago.MercadoPagoWebhookService.Resultado;
import com.mydelivery.service.mercadopago.MercadoPagoWebhookService.WebhookInput;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Endpoint público pro Mercado Pago notificar mudanças de status.
 *
 * MP envia POST com:
 *  - Headers: x-signature, x-request-id
 *  - Query params: type=payment, data.id=<id>, action=payment.updated (forma legada)
 *  - Body JSON: { type, action, data: { id } } (forma nova — coexistem)
 *
 * Aceitamos as duas formas pra robustez. Em caso de divergência, query params prevalecem
 * (é a forma que o MP usa na assinatura HMAC).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MercadoPagoWebhookController {

    private final MercadoPagoWebhookService webhookService;
    private final com.mydelivery.service.WebhookDedupService dedupService;

    @PostMapping("/api/webhooks/mercadopago")
    public ResponseEntity<Void> receber(
            @RequestHeader(value = "x-signature", required = false) String signature,
            @RequestHeader(value = "x-request-id", required = false) String requestId,
            @RequestParam(value = "type", required = false) String typeParam,
            @RequestParam(value = "data.id", required = false) String dataIdParam,
            @RequestParam(value = "action", required = false) String actionParam,
            @RequestBody(required = false) Map<String, Object> body) {

        String tipo = typeParam != null ? typeParam : strDe(body, "type");
        String acao = actionParam != null ? actionParam : strDe(body, "action");
        String dataId = dataIdParam != null ? dataIdParam : dataIdDoBody(body);

        log.info("Webhook MP recebido: type={}, action={}, dataId={}, reqId={}", tipo, acao, dataId, requestId);

        // Dedup: MP reenvia webhook em timeout/retry. Sem isso, pedido virava
        // pago 2x e fidelidade somava pontos duplicados. Chave = (type, dataId).
        // Preferimos type+dataId em vez de requestId porque requestId muda
        // entre retries do mesmo evento — type+dataId identifica o pagamento.
        String chaveDedup = (tipo == null ? "" : tipo) + ":" + (dataId == null ? "" : dataId);
        if (!chaveDedup.equals(":") && !dedupService.tryClaim("mercadopago", chaveDedup)) {
            log.info("Webhook MP duplicado ignorado: {}", chaveDedup);
            return ResponseEntity.ok().build();
        }

        var input = new WebhookInput(requestId, signature, tipo, acao, dataId);
        Resultado r = webhookService.processar(input);

        return switch (r) {
            case OK -> ResponseEntity.ok().build();
            case INVALIDO -> ResponseEntity.status(401).build();
            case ERRO -> ResponseEntity.status(500).build();
        };
    }

    private String strDe(Map<String, Object> body, String k) {
        if (body == null) return null;
        Object v = body.get(k);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private String dataIdDoBody(Map<String, Object> body) {
        if (body == null) return null;
        Object data = body.get("data");
        if (data instanceof Map<?, ?> m) {
            Object id = ((Map<String, Object>) m).get("id");
            return id == null ? null : id.toString();
        }
        return null;
    }
}
