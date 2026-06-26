package com.mydelivery.dto.entregador;

import lombok.Data;

@Data
public class EntregadorLoginRequest {
    /** PIN de 4-8 dígitos (definido pelo dono no painel). */
    private String pin;
}
