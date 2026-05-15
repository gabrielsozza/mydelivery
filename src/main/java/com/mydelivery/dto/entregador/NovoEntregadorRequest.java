package com.mydelivery.dto.entregador;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class NovoEntregadorRequest {
    @NotBlank(message = "Nome obrigatorio")
    private String nome;
    private String telefone;
    private String veiculo;
    private String placa;
}
