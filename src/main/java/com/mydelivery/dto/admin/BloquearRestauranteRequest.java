package com.mydelivery.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BloquearRestauranteRequest {

    @NotBlank(message = "Motivo é obrigatório")
    private String motivo;
}