package com.mydelivery.dto.cardapio;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProdutoRequest {

    @NotBlank(message = "Nome é obrigatório")
    private String nome;

    private String descricao;

    @NotNull(message = "Preço é obrigatório")
    @DecimalMin(value = "0.01", message = "Preço deve ser maior que zero")
    private BigDecimal preco;

    /** Preço normal (sem desconto). Quando preenchido, indica que {@link #preco}
     *  é o preço PROMOCIONAL e este é o "de" riscado pro cliente. Null = sem promo. */
    private BigDecimal precoOriginal;

    private Long categoriaId;
    private String fotoUrl;
    private Boolean disponivel = true;
    private Boolean destaque = false;
}