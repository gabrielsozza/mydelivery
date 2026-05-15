package com.mydelivery.dto.cardapio;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CategoriaRequest {

    @NotBlank(message = "Nome é obrigatório")
    private String nome;

    private Integer ordem = 0;
    private Boolean ativo = true;
}