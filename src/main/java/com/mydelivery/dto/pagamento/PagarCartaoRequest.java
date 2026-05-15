package com.mydelivery.dto.pagamento;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Body do POST /api/pagamentos/cartao.
 *
 * O cardToken vem do SDK MercadoPago.js v2 rodando no browser do cliente.
 * Nosso backend NUNCA vê PAN, CVV ou validade — só o token efêmero (válido por minutos).
 * Isso nos mantém fora de escopo PCI-DSS além do mais básico (SAQ-A).
 */
@Data
public class PagarCartaoRequest {

    @NotNull
    private Long pedidoId;

    /** Token efêmero gerado pelo SDK do MP no browser. Sempre presente. */
    @NotBlank
    private String cardToken;

    /** payment_method_id retornado pelo SDK ("visa", "master", "amex", "elo"...). */
    @NotBlank
    private String paymentMethodId;

    /**
     * Parcelas. 1 = à vista. Opcional: default 1 quando não enviado
     * (fluxo de delivery não oferece parcelamento).
     */
    private Integer parcelas;

    /**
     * E-mail do pagador. Opcional no fluxo de delivery — MP exige um valor
     * mas o backend gera sintético a partir do telefone do cliente quando
     * não enviado pelo frontend (PagamentoService.processarCartao).
     */
    @Email
    private String payerEmail;

    /** Tipo de documento do pagador: "CPF" ou "CNPJ". */
    @NotBlank
    private String payerDocTipo;

    /** Apenas dígitos do documento. */
    @NotBlank
    private String payerDocNumero;
}
