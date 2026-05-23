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
    private final String adminPublicKey;
    private final String adminPayerEmail;
    private final String publicBaseUrl;

    public AssinaturaPagamentoService(
            MercadoPagoClient mpClient,
            @Value("${mydelivery.mercadopago.admin-access-token:${ADMIN_MP_ACCESS_TOKEN:}}") String adminAccessToken,
            @Value("${mydelivery.mercadopago.admin-public-key:${ADMIN_MP_PUBLIC_KEY:}}") String adminPublicKey,
            @Value("${mydelivery.mercadopago.admin-payer-email:${ADMIN_MP_PAYER_EMAIL:billing@mydeliveryfood.com.br}}") String adminPayerEmail,
            @Value("${mydelivery.mercadopago.public-base-url:${MP_WEBHOOK_URL:https://api.mydeliveryfood.com.br}}") String publicBaseUrl) {
        this.mpClient = mpClient;
        this.adminAccessToken = adminAccessToken;
        this.adminPublicKey = adminPublicKey;
        this.adminPayerEmail = adminPayerEmail;
        this.publicBaseUrl = publicBaseUrl;
        this.checkoutClient = RestClient.builder()
                .baseUrl("https://api.mercadopago.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** Public Key MP — exposta pro frontend pra renderizar Card Payment Brick. */
    public Map<String, Object> publicKeyInfo() {
        boolean sandbox = adminAccessToken != null && adminAccessToken.startsWith("TEST-");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("publicKey", adminPublicKey);
        out.put("ambiente", sandbox ? "TEST" : "PROD");
        return out;
    }

    /**
     * Salva o cartão no MP (Customer + Card) SEM cobrar — usado quando o
     * restaurante ainda está em TRIAL. A cobrança real será disparada por job
     * quando trialExpiraEm chegar, usando o card_id permanente do MP.
     *
     * Retorna: { tipo: "AGENDADO", customerId, cardId, cobrarEm }
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> salvarCartaoParaTrial(Restaurante r, Plano plano, Map<String, Object> formData) {
        exigirCredenciais();
        String token = (String) formData.get("token");
        if (token == null || token.isBlank()) throw new RuntimeException("Token do cartão ausente.");

        Map<String, Object> payerRaw = (Map<String, Object>) formData.getOrDefault("payer", Map.of());
        String email = (String) payerRaw.getOrDefault("email", adminPayerEmail);

        // 1) Cria Customer no MP (idempotente: se já existir com esse email, MP devolve o existente)
        Map<String, Object> customerBody = new LinkedHashMap<>();
        customerBody.put("email", email);
        customerBody.put("first_name", safeFirst(r.getNome()));
        customerBody.put("last_name", "MyDelivery");
        customerBody.put("description", "Restaurante #" + r.getId() + " — " + r.getNome());

        Map<String, Object> customer;
        try {
            customer = checkoutClient.post().uri("/v1/customers")
                    .headers(h -> h.setBearerAuth(adminAccessToken))
                    .body(customerBody)
                    .retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            // Se for "already exists", busca o existente
            String body = e.getResponseBodyAsString();
            if (body != null && body.contains("already exist")) {
                Map<String, Object> search = checkoutClient.get()
                        .uri("/v1/customers/search?email={email}", email)
                        .headers(h -> h.setBearerAuth(adminAccessToken))
                        .retrieve().body(Map.class);
                List<Map<String, Object>> results = (List<Map<String, Object>>)
                        (search == null ? List.of() : search.getOrDefault("results", List.of()));
                if (results.isEmpty()) throw new RuntimeException("MP customer já existe mas search falhou");
                customer = results.get(0);
            } else {
                throw new RuntimeException("Falha ao criar customer no MP: " + truncate(body, 200));
            }
        }
        String customerId = String.valueOf(customer.get("id"));

        // 2) Adiciona o cartão ao Customer (usando o token do Brick)
        Map<String, Object> cardBody = Map.of("token", token);
        Map<String, Object> card;
        try {
            card = checkoutClient.post().uri("/v1/customers/{cid}/cards", customerId)
                    .headers(h -> h.setBearerAuth(adminAccessToken))
                    .body(cardBody)
                    .retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            throw new RuntimeException("Falha ao salvar cartão no MP: "
                    + truncate(e.getResponseBodyAsString(), 200));
        }
        String cardId = String.valueOf(card.get("id"));

        // Data prevista da cobrança = quando trial expira (cliente do restaurante decide).
        // Aqui retornamos a referência composta pro Service registrar na Assinatura.
        String refGateway = "trial-card:" + customerId + ":" + cardId;
        log.info("[AssPag][CARTAO][TRIAL] cartão salvo — restaurante={}, customer={}, card={}",
                r.getId(), customerId, cardId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tipo", "AGENDADO");
        out.put("mpCustomerId", customerId);
        out.put("mpCardId", cardId);
        out.put("referenciaGateway", refGateway);
        out.put("aprovado", true); // cartão validado (token aceito pelo MP)
        return out;
    }

    /**
     * Processa pagamento por cartão usando o TOKEN gerado pelo Card Payment Brick.
     * O cartão real NUNCA passa pelo backend — só o token (PCI compliant).
     */
    public Map<String, Object> pagarCartao(Restaurante r, Plano plano, Map<String, Object> formData) {
        exigirCredenciais();
        String token = (String) formData.get("token");
        if (token == null || token.isBlank()) {
            throw new RuntimeException("Token do cartão ausente.");
        }
        Object installmentsRaw = formData.getOrDefault("installments", 1);
        int installments = installmentsRaw instanceof Number ? ((Number) installmentsRaw).intValue()
                : Integer.parseInt(String.valueOf(installmentsRaw));
        String paymentMethodId = (String) formData.getOrDefault("payment_method_id", null);

        // Payer info — vindo do Brick (email + identificação)
        @SuppressWarnings("unchecked")
        Map<String, Object> payerRaw = (Map<String, Object>) formData.getOrDefault("payer", Map.of());
        String email = (String) payerRaw.getOrDefault("email", adminPayerEmail);
        @SuppressWarnings("unchecked")
        Map<String, Object> idRaw = (Map<String, Object>) payerRaw.getOrDefault("identification", Map.of());
        String docType = (String) idRaw.getOrDefault("type", "CPF");
        String docNumber = idRaw.get("number") == null ? null
                : String.valueOf(idRaw.get("number")).replaceAll("\\D", "");

        String idempotencyKey = "mydelivery-assinatura-" + r.getId() + "-" + plano.name()
                + "-cartao-" + System.currentTimeMillis();

        MpPayer payer = MpPayer.builder()
                .email(email)
                .firstName(safeFirst(r.getNome()))
                .lastName("MyDelivery")
                .identification(MpPayer.Identification.builder()
                        .type(docType)
                        .number(docNumber)
                        .build())
                .build();

        MpPaymentRequest body = MpPaymentRequest.builder()
                .transactionAmount(plano.getValor())
                .token(token)
                .installments(installments)
                .paymentMethodId(paymentMethodId)
                .description("MyDelivery — Assinatura " + plano.getNomeExibicao() + " (Restaurante #" + r.getId() + ")")
                .externalReference("assinatura-" + r.getId() + "-" + plano.name() + "-" + System.currentTimeMillis())
                .notificationUrl(publicBaseUrl + "/api/webhooks/mercadopago")
                .payer(payer)
                .build();

        log.info("[AssPag][CARTAO] processando — restaurante={}, plano={}, parcelas={}, idem={}",
                r.getId(), plano, installments, idempotencyKey);
        MpPaymentResponse resp = mpClient.criarPagamento(adminAccessToken, idempotencyKey, body);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentId", resp.getId());
        out.put("status", resp.getStatus());
        out.put("statusDetail", resp.getStatusDetail());
        out.put("aprovado", "approved".equals(resp.getStatus()));
        out.put("valor", plano.getValor());
        log.info("[AssPag][CARTAO] MP respondeu — paymentId={}, status={}, detail={}",
                resp.getId(), resp.getStatus(), resp.getStatusDetail());
        return out;
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

    /**
     * Cobra um cartão JÁ salvo (Customer + Card no MP) — usado pelo job de
     * cobrança automática quando o trial expira ou na renovação mensal.
     *
     * @param referenciaGateway formato "trial-card:CUSTOMER:CARD" salvo na Assinatura
     * @return mesmo formato de pagarCartao(): { paymentId, status, aprovado, valor }
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> cobrarCartaoSalvo(Restaurante r, Plano plano, String referenciaGateway) {
        exigirCredenciais();
        if (referenciaGateway == null || !referenciaGateway.startsWith("trial-card:")) {
            throw new RuntimeException("Referência de cartão inválida: " + referenciaGateway);
        }
        String[] parts = referenciaGateway.split(":");
        if (parts.length != 3) throw new RuntimeException("Formato esperado: trial-card:CUSTOMER:CARD");
        String customerId = parts[1];
        String cardId = parts[2];

        // Pra usar customer+card num Payment, precisamos gerar um TOKEN do card.
        // POST /v1/card_tokens com {card_id} usando autenticação do customer.
        Map<String, Object> tokenBody = Map.of("card_id", cardId);
        Map<String, Object> tokenResp;
        try {
            tokenResp = checkoutClient.post()
                    .uri("/v1/card_tokens?public_key={pk}", adminPublicKey)
                    .body(Map.of("card_id", cardId, "customer_id", customerId))
                    .retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            log.error("[AssPag][CARTAO-SAVED] card_tokens falhou: {}", e.getResponseBodyAsString());
            throw new RuntimeException("Falha ao gerar token do cartão salvo: " + truncate(e.getResponseBodyAsString(), 200));
        }
        String token = String.valueOf(tokenResp.get("id"));

        String idempotencyKey = "mydelivery-renov-" + r.getId() + "-" + plano.name() + "-"
                + System.currentTimeMillis();

        MpPayer payer = MpPayer.builder()
                .email(adminPayerEmail)
                .firstName(safeFirst(r.getNome()))
                .lastName("MyDelivery")
                .build();

        MpPaymentRequest body = MpPaymentRequest.builder()
                .transactionAmount(plano.getValor())
                .token(token)
                .installments(1)
                .description("MyDelivery — Renovação " + plano.getNomeExibicao() + " (Restaurante #" + r.getId() + ")")
                .externalReference("renov-" + r.getId() + "-" + plano.name() + "-" + System.currentTimeMillis())
                .notificationUrl(publicBaseUrl + "/api/webhooks/mercadopago")
                .payer(payer)
                .build();

        log.info("[AssPag][CARTAO-SAVED] cobrando — restaurante={}, plano={}, customer={}, card={}",
                r.getId(), plano, customerId, cardId);
        MpPaymentResponse resp = mpClient.criarPagamento(adminAccessToken, idempotencyKey, body);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentId", resp.getId());
        out.put("status", resp.getStatus());
        out.put("statusDetail", resp.getStatusDetail());
        out.put("aprovado", "approved".equals(resp.getStatus()));
        out.put("valor", plano.getValor());
        return out;
    }

    /**
     * Substitui o cartão salvo. Remove o anterior, adiciona o novo.
     * Retorna nova referenciaGateway (trial-card:CUSTOMER:NEW_CARD).
     */
    @SuppressWarnings("unchecked")
    public String atualizarCartao(Restaurante r, String referenciaGatewayAtual, Map<String, Object> formData) {
        exigirCredenciais();
        String token = (String) formData.get("token");
        if (token == null) throw new RuntimeException("Token do cartão ausente.");

        // Se já existia customer, reusa; senão cria novo
        String customerId;
        if (referenciaGatewayAtual != null && referenciaGatewayAtual.startsWith("trial-card:")) {
            customerId = referenciaGatewayAtual.split(":")[1];
        } else {
            Map<String, Object> payerRaw = (Map<String, Object>) formData.getOrDefault("payer", Map.of());
            String email = (String) payerRaw.getOrDefault("email", adminPayerEmail);
            Map<String, Object> customer = checkoutClient.post().uri("/v1/customers")
                    .headers(h -> h.setBearerAuth(adminAccessToken))
                    .body(Map.of("email", email, "first_name", safeFirst(r.getNome()), "last_name", "MyDelivery"))
                    .retrieve().body(Map.class);
            customerId = String.valueOf(customer.get("id"));
        }

        // Adiciona o novo cartão
        Map<String, Object> card = checkoutClient.post().uri("/v1/customers/{cid}/cards", customerId)
                .headers(h -> h.setBearerAuth(adminAccessToken))
                .body(Map.of("token", token))
                .retrieve().body(Map.class);
        String newCardId = String.valueOf(card.get("id"));

        log.info("[AssPag][CARTAO-UPDATE] cartão atualizado — restaurante={}, customer={}, novoCard={}",
                r.getId(), customerId, newCardId);
        return "trial-card:" + customerId + ":" + newCardId;
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
