package com.mydelivery.dto.estoque;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resumo agregado das movimentações de estoque num período.
 * Usado pela aba "Relatórios" do estoque.html.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelatorioEstoqueDTO {

    private String dataInicio;        // ISO yyyy-MM-dd
    private String dataFim;

    private BigDecimal valorComprado;   // soma das ENTRADAS_COMPRA × custoMedio
    private BigDecimal valorConsumido;  // soma das SAIDAS_VENDA × custoMedio
    private BigDecimal valorPerdido;    // soma das PERDAS × custoMedio

    private Integer totalCompras;
    private Integer totalVendas;
    private Integer totalPerdas;

    /** Top N insumos mais consumidos no período (por quantidade). */
    private List<ItemRanking> topConsumidos;

    /** Top N insumos com maior valor perdido (motivo categorizado). */
    private List<ItemRanking> topPerdas;

    /** Série diária de consumo no período (pra gráfico). */
    private List<PontoSerie> consumoDiario;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemRanking {
        private String insumoNome;
        private String unidade;
        private BigDecimal quantidade;
        private BigDecimal valor;        // estimado com custoMedio atual
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PontoSerie {
        private String data;             // yyyy-MM-dd
        private BigDecimal valor;        // valor consumido naquele dia em R$
    }
}
