package com.mydelivery.dto.pagamento;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Body do POST /api/pagamentos/pix.
 * pedidoId já identifica o restaurante (via Pedido → Restaurante) — não precisa enviar tenant separado.
 */
@Data
public class PagarPixRequest {

    @NotNull
    private Long pedidoId;

    /** Email do pagador. MP recomenda fortemente — usado pra notificar e detectar fraude. */
    @Email
    private String payerEmail;

    /** Nome do pagador (display only). */
    private String payerNome;
}
