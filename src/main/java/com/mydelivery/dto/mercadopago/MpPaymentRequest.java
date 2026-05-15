package com.mydelivery.dto.mercadopago;

import java.math.BigDecimal;

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
}
