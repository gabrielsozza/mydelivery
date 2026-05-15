package com.mydelivery.dto.publico;

import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CategoriaPublicResponse {
    private Long id;
    private String nome;
    private List<ProdutoPublicResponse> produtos;
}