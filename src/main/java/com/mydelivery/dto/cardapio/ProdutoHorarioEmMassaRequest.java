package com.mydelivery.dto.cardapio;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProdutoHorarioEmMassaRequest {

    @NotEmpty(message = "Selecione ao menos um produto.")
    @Size(max = 100, message = "Selecione no máximo 100 produtos por vez.")
    private List<@NotNull Long> produtoIds;

    private String diasSemanaAtivos;
    private String horarioInicio;
    private String horarioFim;
}
