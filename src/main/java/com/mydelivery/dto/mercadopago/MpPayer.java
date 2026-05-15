package com.mydelivery.dto.mercadopago;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payer enviado ao MP no body do POST /v1/payments.
 * Campos opcionais omitidos com @JsonInclude(NON_NULL) — alguns são obrigatórios
 * só pra cartão (identification), outros só pra PIX (email).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MpPayer {
    private String email;
    /** "customer" | "registered" — pra PIX basta o email. */
    private String type;
    /** Nome de exibição pro recibo do PIX. */
    @com.fasterxml.jackson.annotation.JsonProperty("first_name")
    private String firstName;
    @com.fasterxml.jackson.annotation.JsonProperty("last_name")
    private String lastName;

    /** CPF/CNPJ — obrigatório pra cartão de crédito no Brasil. */
    private Identification identification;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Identification {
        /** "CPF" ou "CNPJ". */
        private String type;
        /** Só dígitos. */
        private String number;
    }
}
