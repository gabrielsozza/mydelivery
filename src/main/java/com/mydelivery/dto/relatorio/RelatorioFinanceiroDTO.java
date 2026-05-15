package com.mydelivery.dto.relatorio;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Relatório financeiro completo de um período.
 * Inclui KPIs, variações vs período anterior (mesma duração),
 * agregações por canal/pagamento, gráfico diário, top produtos,
 * matriz BCG e insights textuais.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelatorioFinanceiroDTO {

    // ── Período ──
    private String dataInicio;          // ISO yyyy-MM-dd
    private String dataFim;
    private int totalDias;

    // ── KPIs principais ──
    private BigDecimal faturamento;
    private BigDecimal cmv;             // Custo da Mercadoria Vendida
    private BigDecimal margem;          // faturamento - cmv
    private BigDecimal margemPercent;   // 0-100
    private int totalPedidos;
    private BigDecimal ticketMedio;

    // ── Comparação com período anterior (mesmo número de dias antes) ──
    private BigDecimal faturamentoAnterior;
    private BigDecimal variacaoFaturamento;  // % (pode ser negativa)
    private int pedidosAnteriores;
    private BigDecimal variacaoPedidos;
    private BigDecimal ticketAnterior;
    private BigDecimal variacaoTicket;

    // ── Distribuições ──
    private List<SerieItem> receitaPorCanal;       // DELIVERY/RETIRADA/MESA
    private List<SerieItem> receitaPorPagamento;   // PIX/CARTAO/DINHEIRO/etc

    // ── Gráfico de faturamento diário ──
    private List<PontoSerie> faturamentoDiario;

    // ── Top produtos (até 10) ──
    private List<ProdutoStat> topProdutos;

    // ── Matriz BCG (inovação) ──
    /** Todos os produtos vendidos no período, classificados em quadrantes. */
    private List<ProdutoBCG> matrizBCG;

    // ── Insights automáticos (texto interpretativo) ──
    private List<Insight> insights;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SerieItem {
        private String label;
        private BigDecimal valor;
        private int quantidade;
        private BigDecimal percentual;     // 0-100
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PontoSerie {
        private String data;               // yyyy-MM-dd
        private BigDecimal faturamento;
        private int pedidos;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProdutoStat {
        private String nome;
        private int quantidade;
        private BigDecimal receita;
        private BigDecimal custo;          // CMV do produto no período
        private BigDecimal margem;
        private BigDecimal margemPercent;  // 0-100
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProdutoBCG {
        private String nome;
        private int quantidade;
        private BigDecimal receita;
        private BigDecimal margemPercent;
        /** "ESTRELA" | "VACA_LEITEIRA" | "DUVIDA" | "ABACAXI" */
        private String quadrante;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Insight {
        /** "positivo" | "negativo" | "neutro" — pra colorir card no front */
        private String tipo;
        /** Ícone semântico: trending-up / trending-down / star / alert / info */
        private String icone;
        private String mensagem;
    }
}
