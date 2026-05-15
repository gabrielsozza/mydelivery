package com.mydelivery.dto.cardapio;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CategoriaComProdutosResponse {
    private Long id;
    private String nome;
    private Integer ordem;
    private List<ProdutoResponse> produtos;
}