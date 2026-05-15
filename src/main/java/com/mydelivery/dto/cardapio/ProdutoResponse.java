package com.mydelivery.dto.cardapio;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProdutoResponse {
    private Long id;
    private String nome;
    private String descricao;
    private BigDecimal preco;
    private String fotoUrl;
    private Boolean disponivel;
    private Boolean destaque;
    private Long categoriaId;
    private String categoriaNome;
}