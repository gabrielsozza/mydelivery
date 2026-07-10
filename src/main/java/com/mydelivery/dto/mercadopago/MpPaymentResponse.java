package com.mydelivery.dto.mercadopago;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resposta do MP em POST /v1/payments e GET /v1/payments/{id}.
 * Ignoramos campos desconhecidos — MP adiciona coisas com frequência.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MpPaymentResponse {

    private Long id;

    /**
     * approved | pending | in_process | rejected | refunded | cancelled | charged_back | authorized
     * https://www.mercadopago.com.br/developers/pt/docs/checkout-api/payment-management/check-status
     */
    private String status;

    /** status_detail explica o porquê (ex: "cc_rejected_other_reason", "accredited"). */
    @JsonProperty("status_detail")
    private String statusDetail;

    @JsonProperty("transaction_amount")
    private BigDecimal transactionAmount;

    @JsonProperty("payment_method_id")
    private String paymentMethodId;

    /** "credit_card", "pix", "debit_card"... */
    @JsonProperty("payment_type_id")
    private String paymentTypeId;

    @JsonProperty("external_reference")
    private String externalReference;

    /**
     * Data em que o pagamento foi aprovado (ISO 8601 com timezone, ex:
     * "2026-07-05T14:30:00.000-03:00"). Usada pela reconciliação manual
     * pra registrar {@code PagamentoMensalidade.pagoEm} com a data REAL
     * do MP (não a hora em que o admin rodou o batch). Nulo quando ainda
     * não aprovado.
     */
    @JsonProperty("date_approved")
    private String dateApproved;

    /** Detalhes específicos do PIX. */
    @JsonProperty("point_of_interaction")
    private PointOfInteraction pointOfInteraction;

    /** Dados do cartão (só preenchido quando payment_type_id = credit_card). */
    private Card card;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PointOfInteraction {
        @JsonProperty("transaction_data")
        private TransactionData transactionData;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransactionData {
        /** Copia e cola PIX (BR Code EMV). */
        @JsonProperty("qr_code")
        private String qrCode;

        /** PNG do QR Code em base64 (sem prefixo data:). */
        @JsonProperty("qr_code_base64")
        private String qrCodeBase64;

        /** ID do PIX no banco central (TxID). */
        @JsonProperty("ticket_url")
        private String ticketUrl;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Card {
        @JsonProperty("last_four_digits")
        private String lastFourDigits;
    }
}
