package com.mydelivery.dto.cupom;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidarCupomResponse {
    private boolean valido;
    private String codigo;
    private String tipo;
    private BigDecimal desconto;        // valor calculado em R$ (0 para ITEM_GRATIS)
    private String descricao;           // pra ITEM_GRATIS, descreve o brinde
    private String mensagem;            // erro ou confirmação ("Cupom aplicado!")
}
