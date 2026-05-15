package com.mydelivery.dto.estoque;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * Ficha técnica de um produto = lista de insumos com quantidades.
 * Usado tanto pra ler quanto pra salvar (substitui a ficha inteira).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FichaTecnicaDTO {
    private Long produtoId;
    private String produtoNome;
    private List<Item> itens;

    @Data
    public static class Item {
        private Long insumoId;
        private String insumoNome;
        private String unidade;
        private BigDecimal quantidade;
        private BigDecimal saldoAtualInsumo;  // só pra info na UI
    }
}
