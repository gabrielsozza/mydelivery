package com.mydelivery.dto.cupom;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class ValidarCupomRequest {
    private String codigo;
    private String slug;
    private String telefone;        // pra checar limite por cliente e cupom de fidelidade
    private BigDecimal subtotal;
    private String modo;            // delivery, retirada, mesa
}
