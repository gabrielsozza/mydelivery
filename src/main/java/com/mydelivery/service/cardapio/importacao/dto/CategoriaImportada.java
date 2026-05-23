package com.mydelivery.service.cardapio.importacao.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoriaImportada {
    private String nome;
    @Builder.Default
    private List<ProdutoImportado> produtos = new ArrayList<>();
}
