package com.mydelivery.dto.mercadopago;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body do POST https://api.mercadopago.com/v1/payments.
 *
 * Doc: https://www.mercadopago.com.br/developers/pt/reference/payments/_payments/post
 *
 * Para PIX: payment_method_id = "pix", sem token.
 * Para cartão: token (gerado pelo SDK do MP no browser) + installments + payment_method_id ("visa", "master"...).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MpPaymentRequest {

    @JsonProperty("transaction_amount")
    private BigDecimal transactionAmount;

    /** "pix", "visa", "master", "amex" etc. — pra cartão o SDK informa. */
    @JsonProperty("payment_method_id")
    private String paymentMethodId;

    /** Token efêmero gerado pelo MercadoPago.js v2 no browser. Só pra cartão. */
    private String token;

    /** Número de parcelas. Default 1. Só pra cartão de crédito. */
    private Integer installments;

    /** Descrição que aparece pro pagador (extrato, notificação). Máx ~256 chars. */
    private String description;

    /**
     * external_reference é o nosso ID do pedido. MP devolve em todo webhook —
     * é como achamos o Pedido local sem confiar em mais nada do payload.
     */
    @JsonProperty("external_reference")
    private String externalReference;

    /** URL pública pro MP notificar mudanças. Sobrescreve a config global do app no MP. */
    @JsonProperty("notification_url")
    private String notificationUrl;

    /**
     * Expiração do PIX em formato ISO-8601 com offset (ex: 2026-05-14T15:30:00.000-03:00).
     * MP exige esse formato exato; usar DateTimeFormatter.ISO_OFFSET_DATE_TIME.
     */
    @JsonProperty("date_of_expiration")
    private String dateOfExpiration;

    private MpPayer payer;

    /**
     * Dados adicionais usados pelo MOTOR ANTIFRAUDE do MP. Quanto mais dados aqui,
     * MENOR o risco de "high_risk" rejection. Estrutura típica:
     *   { "items": [...], "payer": {...}, "shipments": {...} }
     * Usamos Map em vez de DTO tipado porque a estrutura é grande e raramente muda.
     */
    @JsonProperty("additional_info")
    private Map<String, Object> additionalInfo;

    /**
     * 3-D Secure (3DS) — verificação adicional via banco emissor (OTP/biometria).
     * Valores: "optional" (recomendado), "mandatory", null (desligado).
     * "optional" deixa MP escolher: alto risco vira "pending_challenge", baixo risco passa direto.
     * Aumenta drasticamente a taxa de aprovação porque transfere a verificação pro banco.
     */
    @JsonProperty("three_d_secure_mode")
    private String threeDSecureMode;

    /**
     * Texto que aparece na fatura do cartão do cliente (máx ~22 chars).
     * Ajuda na identificação do estabelecimento e reduz chargebacks por desconhecimento.
     */
    @JsonProperty("statement_descriptor")
    private String statementDescriptor;

    /**
     * Decisão binária: true = MP aprova/rejeita IMEDIATAMENTE, sem "pending".
     * Recomendado pra delivery (cliente espera resposta, não quer payment em limbo).
     * Trade-off: alguns pagamentos que ficariam pending pra revisão viram rejeição direta.
     */
    @JsonProperty("binary_mode")
    private Boolean binaryMode;
}
