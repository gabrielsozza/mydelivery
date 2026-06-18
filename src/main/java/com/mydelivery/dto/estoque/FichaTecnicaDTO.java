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
        /** Unidade do INSUMO (somente leitura, vem do cadastro). */
        private String unidade;
        private BigDecimal quantidade;
        /**
         * Unidade da RECEITA — pode diferir de {@code unidade} desde que
         * seja do mesmo grupo (volume ou massa). Ex: insumo em L, receita
         * em ML. Quando null/vazio, assume mesma unidade do insumo.
         */
        private String unidadeReceita;
        private BigDecimal saldoAtualInsumo;  // só pra info na UI
    }
}
