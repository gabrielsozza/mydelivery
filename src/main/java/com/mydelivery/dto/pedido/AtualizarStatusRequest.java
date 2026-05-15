package com.mydelivery.dto.pedido;

import com.mydelivery.model.Pedido;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AtualizarStatusRequest {

    @NotNull(message = "Status é obrigatório")
    private Pedido.Status status;
}