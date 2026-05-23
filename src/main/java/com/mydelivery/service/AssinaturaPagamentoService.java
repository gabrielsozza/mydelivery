package com.mydelivery.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.mydelivery.dto.mercadopago.MpPayer;
import com.mydelivery.dto.mercadopago.MpPaymentRequest;
import com.mydelivery.dto.mercadopago.MpPaymentResponse;
import com.mydelivery.model.Plano;
import com.mydelivery.model.Restaurante;
import com.mydelivery.service.mercadopago.MercadoPagoClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Gera cobrança Mercado Pago para ASSINATURAS (não confundir com pagamentos
 * de pedidos — esse usa as creds do RESTAURANTE).
 *
 * Aqui usamos as creds da MyDelivery (admin) — env {@code ADMIN_MP_ACCESS_TOKEN}.
 *
 * - PIX:    cria Payment direto (/v1/payments) e retorna QR code + chave copia-cola
 * - CARTÃO: cria Preference (/checkout/preferences) e retorna init_point (URL Checkout Pro)
 *           — usuário é redirecionado e o MP cuida do form de cartão.
 */
@Slf4j
@Service
public class AssinaturaPagamentoService {

    private final MercadoPagoClient mpClient;
    private final RestClient checkoutClient;
    private final String adminAccessToken;
    private final String adminPayerEmail;
    private final String publicBaseUrl;

    public AssinaturaPagamentoService(
            MercadoPagoClient mpClient,
            @Value("${mydelivery.mercadopago.admin-access-token:${ADMIN_MP_ACCESS_TOKEN:}}") String adminAccessToken,
            @Value("${mydelivery.mercadopago.admin-payer-email:${ADMIN_MP_PAYER_EMAIL:billing@mydeliveryfood.com.br}}") String adminPayerEmail,
            @Value("${mydelivery.mercadopago.public-base-url:${MP_WEBHOOK_URL:https://api.mydeliveryfood.com.br}}") String publicBaseUrl) {
        this.mpClient = mpClient;
        this.adminAccessToken = adminAccessToken;
        this.adminPayerEmail = adminPayerEmail;
        this.publicBaseUrl = publicBaseUrl;
        this.checkoutClient = RestClient.builder()
                .baseUrl("https://api.mercadopago.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Cria cobrança PIX. Retorna { qrCode, qrCodeBase64, paymentId, valor, expiraEm }.
     */
    public Map<String, Object> criarPix(Restaurante r, Plano plano) {
        exigirCredenciais();
        String idempotencyKey = "mydelivery-assinatura-" + r.getId() + "-" + plano.name()
                + "-pix-" + System.currentTimeMillis();
        LocalDateTime expiraEm = LocalDateTime.now().plusMinutes(30);

        MpPayer payer = MpPayer.builder()
                .email(adminPayerEmail)
                .firstName(safeFirst(r.getNome()))
                .lastName("MyDelivery")
                .build();

        MpPaymentRequest body = MpPaymentRequest.builder()
                .transactionAmount(plano.getValor())
                .paymentMethodId("pix")
                .description("MyDelivery — Assinatura " + plano.getNomeExibicao() + " (Restaurante #" + r.getId() + ")")
                .externalReference("assinatura-" + r.getId() + "-" + plano.name() + "-" + System.currentTimeMillis())
                .notificationUrl(publicBaseUrl + "/api/webhooks/mercadopago")
                .dateOfExpiration(formatarExpiracao(expiraEm))
                .payer(payer)
                .build();

        log.info("[AssPag][PIX] criando — restaurante={}, plano={}, valor={}, idem={}",
                r.getId(), plano, plano.getValor(), idempotencyKey);
        MpPaymentResponse resp = mpClient.criarPagamento(adminAccessToken, idempotencyKey, body);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tipo", "PIX");
        out.put("paymentId", resp.getId());
        out.put("status", resp.getStatus());
        out.put("valor", plano.getValor());
        out.put("expiraEm", expiraEm.toString());
        if (resp.getPointOfInteraction() != null
                && resp.getPointOfInteraction().getTransactionData() != null) {
            out.put("qrCode", resp.getPointOfInteraction().getTransactionData().getQrCode());
            out.put("qrCodeBase64", resp.getPointOfInteraction().getTransactionData().getQrCodeBase64());
        }
        return out;
    }

    /**
     * Cria Preference (Checkout Pro) pra cartão. Retorna { checkoutUrl, preferenceId }.
     * O usuário é redirecionado pro Mercado Pago — ele cuida do formulário.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> criarCheckoutCartao(Restaurante r, Plano plano, String returnBaseUrl) {
        exigirCredenciais();
        String externalRef = "assinatura-" + r.getId() + "-" + plano.name() + "-" + System.currentTimeMillis();
        // Limita 256 chars conforme MP. Usa returnBaseUrl pra redirect (preview/prod diferentes).
        String backSuccess = returnBaseUrl + "/planos.html?mp=success&ref=" + externalRef;
        String backFailure = returnBaseUrl + "/planos.html?mp=failure&ref=" + externalRef;
        String backPending = returnBaseUrl + "/planos.html?mp=pending&ref=" + externalRef;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(Map.of(
                "id", plano.name(),
                "title", "MyDelivery — Assinatura " + plano.getNomeExibicao(),
                "description", "Plano " + plano.getNomeExibicao() + " (" + plano.getDuracaoMeses() + " mês(es))",
                "quantity", 1,
                "currency_id", "BRL",
                "unit_price", plano.getValor()
        )));
        body.put("payer", Map.of("email", adminPayerEmail));
        body.put("payment_methods", Map.of(
                "excluded_payment_types", List.of(Map.of("id", "ticket"), Map.of("id", "atm")), // só cartão
                "installments", 12
        ));
        body.put("back_urls", Map.of(
                "success", backSuccess,
                "failure", backFailure,
                "pending", backPending
        ));
        body.put("auto_return", "approved");
        body.put("external_reference", externalRef);
        body.put("notification_url", publicBaseUrl + "/api/webhooks/mercadopago");
        body.put("statement_descriptor", "MYDELIVERY");
        body.put("metadata", Map.of(
                "tipo", "ASSINATURA",
                "restaurante_id", r.getId(),
                "plano", plano.name()
        ));

        try {
            String idem = UUID.randomUUID().toString();
            Map<String, Object> resp = checkoutClient.post()
                    .uri("/checkout/preferences")
                    .headers(h -> {
                        h.setBearerAuth(adminAccessToken);
                        h.add("X-Idempotency-Key", idem);
                    })
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tipo", "CHECKOUT_URL");
            out.put("preferenceId", resp != null ? resp.get("id") : null);
            // sandbox_init_point se token TEST-...; init_point se APP_USR
            String url = resp == null ? null : (String) (adminAccessToken.startsWith("TEST-")
                    ? resp.getOrDefault("sandbox_init_point", resp.get("init_point"))
                    : resp.get("init_point"));
            out.put("checkoutUrl", url);
            out.put("externalReference", externalRef);
            log.info("[AssPag][CARTAO] preference criada — restaurante={}, plano={}, ref={}",
                    r.getId(), plano, externalRef);
            return out;
        } catch (RestClientResponseException e) {
            log.error("[AssPag][CARTAO] MP /checkout/preferences falhou [{}]: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Falha ao criar checkout no Mercado Pago: "
                    + truncate(e.getResponseBodyAsString(), 200));
        }
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────

    private void exigirCredenciais() {
        if (adminAccessToken == null || adminAccessToken.isBlank()
                || "APP_USR-...seu_token_de_produção_ou_TEST...".equals(adminAccessToken)) {
            throw new RuntimeException("ADMIN_MP_ACCESS_TOKEN não configurado no backend. "
                    + "Configure no Railway antes de cobrar assinaturas.");
        }
    }

    private static String safeFirst(String s) {
        if (s == null || s.isBlank()) return "Restaurante";
        String[] parts = s.trim().split("\\s+", 2);
        return parts[0];
    }

    /** MP exige ISO-8601 com offset, ex: 2026-05-23T10:00:00.000-03:00 */
    private static String formatarExpiracao(LocalDateTime ldt) {
        return ldt.atZone(ZoneOffset.of("-03:00"))
                .toOffsetDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /** Wrapper utility — opcional pra usos futuros (consultar status do pagamento). */
    @SuppressWarnings("unused")
    private OffsetDateTime _ignore() { return OffsetDateTime.now(); }

    /** Wrapper utility — opcional. */
    @SuppressWarnings("unused")
    private BigDecimal _ignore2() { return BigDecimal.ZERO; }
}
