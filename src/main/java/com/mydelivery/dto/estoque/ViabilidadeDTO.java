package com.mydelivery.dto.estoque;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * "Você consegue vender N unidades de X-Burguer com seu estoque atual" — a inovação.
 *
 * Pra cada produto que TEM ficha técnica cadastrada, calcula quantas unidades
 * são produzíveis pelo estoque atual. Quando = 0, retorna qual insumo está em falta.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ViabilidadeDTO {
    private Long produtoId;
    private String produtoNome;
    private Integer unidadesProduziveis;
    /** Insumo que limita a produção (o "gargalo"). Null se ainda sobra muito. */
    private String insumoLimitante;
    /** True quando 0 unidades podem ser feitas (ruptura). */
    private boolean ruptura;

    public static ViabilidadeDTO semFicha(Long id, String nome) {
        return ViabilidadeDTO.builder()
                .produtoId(id).produtoNome(nome)
                .unidadesProduziveis(null) // null = "sem ficha técnica"
                .ruptura(false)
                .build();
    }

    /** Container retornado pelo endpoint */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Resumo {
        private List<ViabilidadeDTO> produtos;
        private int totalEmRuptura;       // quantos produtos sem condições de produzir
        private int totalInsumosBaixos;   // quantos insumos abaixo do mínimo
    }
}
