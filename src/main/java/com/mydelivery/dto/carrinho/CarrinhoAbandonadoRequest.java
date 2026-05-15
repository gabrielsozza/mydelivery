package com.mydelivery.dto.carrinho;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

@Data
public class CarrinhoAbandonadoRequest {

    private String sessionId;       // gerado no frontend, persiste na sessão
    private String slugRestaurante;
    private String nomeCliente;
    private String telefoneCliente;

    private List<ItemCarrinhoDto> itens;

    @Data
    public static class ItemCarrinhoDto {
        private Long produtoId;
        private String nomeProduto;
        private Integer quantidade;
        private BigDecimal precoUnitario;
        private String observacao;
    }
}