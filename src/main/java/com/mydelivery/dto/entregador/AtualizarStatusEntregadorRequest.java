package com.mydelivery.dto.entregador;

import com.mydelivery.model.Entregador;
import lombok.Data;

@Data
public class AtualizarStatusEntregadorRequest {
    private Entregador.Status status;
}
