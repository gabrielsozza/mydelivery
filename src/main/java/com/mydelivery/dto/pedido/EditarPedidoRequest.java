package com.mydelivery.dto.pedido;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class EditarPedidoRequest {

    @NotEmpty(message = "O pedido deve ter ao menos um item")
    private List<ItemDto> itens;

    private String observacao;

    @Data
    public static class ItemDto {
        private Long id;
        private Long produtoId;
        private String nomeProduto;
        private Integer quantidade;
        private BigDecimal precoUnitario;
        private String observacao;
    }
}