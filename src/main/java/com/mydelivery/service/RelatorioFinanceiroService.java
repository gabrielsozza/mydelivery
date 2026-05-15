package com.mydelivery.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.relatorio.RelatorioFinanceiroDTO;
import com.mydelivery.model.FichaTecnicaItem;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.PedidoItem;
import com.mydelivery.repository.FichaTecnicaItemRepository;
import com.mydelivery.repository.PedidoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Calcula o relatório financeiro completo de um período.
 *
 * Inclui:
 *  - KPIs (faturamento, CMV, margem, ticket médio)
 *  - Variação % vs período anterior de mesma duração
 *  - Receita por canal e por forma de pagamento
 *  - Série diária pra gráfico
 *  - Top produtos por receita
 *  - Matriz BCG (estrela / vaca leiteira / dúvida / abacaxi)
 *  - Insights textuais gerados por heurística
 *
 * CMV é calculado via ficha técnica do produto × custo médio dos insumos.
 * Produtos sem ficha contribuem 0 pro CMV (margem aparente = 100%).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelatorioFinanceiroService {

    private final PedidoRepository pedidoRepository;
    private final FichaTecnicaItemRepository fichaRepository;

    // readOnly=true mantém a sessão Hibernate aberta durante a iteração de Pedido.itens (lazy)
    // e evita LazyInitializationException ao calcular CMV e agregar produtos.
    @Transactional(readOnly = true)
    public RelatorioFinanceiroDTO gerar(Long restauranteId, LocalDate dataIni, LocalDate dataFim) {
        LocalDateTime ini = dataIni.atStartOfDay();
        LocalDateTime fim = dataFim.atTime(23, 59, 59);

        int dias = (int) ChronoUnit.DAYS.between(dataIni, dataFim) + 1;

        // Pedidos do período (só os que viraram receita — exclui CANCELADO e AGUARDANDO_PAGAMENTO)
        List<Pedido> pedidos = pedidoRepository
                .findByRestauranteIdAndPeriodo(restauranteId, ini, fim).stream()
                .filter(this::contaParaReceita)
                .toList();

        // Pra período anterior: mesma duração, terminando no dia antes do período atual
        LocalDate anteriorFim = dataIni.minusDays(1);
        LocalDate anteriorIni = anteriorFim.minusDays(dias - 1);
        List<Pedido> pedidosAnt = pedidoRepository
                .findByRestauranteIdAndPeriodo(restauranteId,
                        anteriorIni.atStartOfDay(), anteriorFim.atTime(23, 59, 59))
                .stream().filter(this::contaParaReceita).toList();

        // Mapa de CMV por produto (pra reutilizar) — produto.id → custo unitário
        Map<Long, BigDecimal> custoPorProduto = montarCustoPorProduto(restauranteId);

        // KPIs atuais
        BigDecimal faturamento = somaTotal(pedidos);
        BigDecimal cmv = calcularCMV(pedidos, custoPorProduto);
        BigDecimal margem = faturamento.subtract(cmv);
        BigDecimal margemPct = percentual(margem, faturamento);
        BigDecimal ticket = pedidos.isEmpty() ? BigDecimal.ZERO
                : faturamento.divide(BigDecimal.valueOf(pedidos.size()), 2, RoundingMode.HALF_UP);

        // KPIs anteriores
        BigDecimal faturamentoAnt = somaTotal(pedidosAnt);
        BigDecimal ticketAnt = pedidosAnt.isEmpty() ? BigDecimal.ZERO
                : faturamentoAnt.divide(BigDecimal.valueOf(pedidosAnt.size()), 2, RoundingMode.HALF_UP);

        // Distribuições
        var porCanal = agregarPorCanal(pedidos, faturamento);
        var porPagamento = agregarPorPagamento(pedidos, faturamento);
        var serie = serieDiaria(pedidos, dataIni, dataFim);

        // Top produtos + matriz BCG (mesma agregação base)
        var statsProdutos = agregarProdutos(pedidos, custoPorProduto);
        var topProdutos = statsProdutos.stream()
                .sorted(Comparator.comparing(RelatorioFinanceiroDTO.ProdutoStat::getReceita).reversed())
                .limit(10).toList();
        var bcg = matrizBCG(statsProdutos);

        // Insights automáticos
        var insights = gerarInsights(faturamento, faturamentoAnt, pedidos.size(), pedidosAnt.size(),
                ticket, ticketAnt, margemPct, porCanal, statsProdutos);

        return RelatorioFinanceiroDTO.builder()
                .dataInicio(dataIni.toString())
                .dataFim(dataFim.toString())
                .totalDias(dias)
                .faturamento(faturamento)
                .cmv(cmv)
                .margem(margem)
                .margemPercent(margemPct)
                .totalPedidos(pedidos.size())
                .ticketMedio(ticket)
                .faturamentoAnterior(faturamentoAnt)
                .variacaoFaturamento(variacao(faturamento, faturamentoAnt))
                .pedidosAnteriores(pedidosAnt.size())
                .variacaoPedidos(variacao(BigDecimal.valueOf(pedidos.size()), BigDecimal.valueOf(pedidosAnt.size())))
                .ticketAnterior(ticketAnt)
                .variacaoTicket(variacao(ticket, ticketAnt))
                .receitaPorCanal(porCanal)
                .receitaPorPagamento(porPagamento)
                .faturamentoDiario(serie)
                .topProdutos(topProdutos)
                .matrizBCG(bcg)
                .insights(insights)
                .build();
    }

    // ── HELPERS ──────────────────────────────────────────────────────────────

    private boolean contaParaReceita(Pedido p) {
        return p.getStatus() != Pedido.Status.CANCELADO
                && p.getStatus() != Pedido.Status.AGUARDANDO_PAGAMENTO;
    }

    private BigDecimal somaTotal(List<Pedido> pedidos) {
        return pedidos.stream()
                .map(p -> p.getTotal() != null ? p.getTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Custo por unidade de cada produto, baseado na ficha técnica × custo médio dos insumos. */
    private Map<Long, BigDecimal> montarCustoPorProduto(Long restauranteId) {
        Map<Long, BigDecimal> map = new HashMap<>();
        var fichas = fichaRepository.findByProdutoRestauranteId(restauranteId);
        for (FichaTecnicaItem f : fichas) {
            if (f.getProduto() == null || f.getInsumo() == null) continue;
            BigDecimal custoUnit = f.getInsumo().getCustoMedio() != null
                    ? f.getInsumo().getCustoMedio() : BigDecimal.ZERO;
            BigDecimal qtd = f.getQuantidade() != null ? f.getQuantidade() : BigDecimal.ZERO;
            BigDecimal custo = qtd.multiply(custoUnit);
            map.merge(f.getProduto().getId(), custo, BigDecimal::add);
        }
        return map;
    }

    private BigDecimal calcularCMV(List<Pedido> pedidos, Map<Long, BigDecimal> custoPorProduto) {
        BigDecimal total = BigDecimal.ZERO;
        for (Pedido p : pedidos) {
            if (p.getItens() == null) continue;
            for (PedidoItem item : p.getItens()) {
                if (item.getProduto() == null) continue;
                BigDecimal custoUnit = custoPorProduto.getOrDefault(item.getProduto().getId(), BigDecimal.ZERO);
                int qtd = item.getQuantidade() != null ? item.getQuantidade() : 0;
                total = total.add(custoUnit.multiply(BigDecimal.valueOf(qtd)));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private List<RelatorioFinanceiroDTO.SerieItem> agregarPorCanal(List<Pedido> pedidos, BigDecimal totalGeral) {
        Map<String, BigDecimal> rec = new LinkedHashMap<>();
        Map<String, Integer> cnt = new HashMap<>();
        for (Pedido p : pedidos) {
            String canal = p.getTipo() != null ? p.getTipo().name() : "OUTRO";
            rec.merge(canal, p.getTotal() != null ? p.getTotal() : BigDecimal.ZERO, BigDecimal::add);
            cnt.merge(canal, 1, Integer::sum);
        }
        return rec.entrySet().stream()
                .map(e -> RelatorioFinanceiroDTO.SerieItem.builder()
                        .label(e.getKey())
                        .valor(e.getValue())
                        .quantidade(cnt.get(e.getKey()))
                        .percentual(percentual(e.getValue(), totalGeral))
                        .build())
                .sorted(Comparator.comparing(RelatorioFinanceiroDTO.SerieItem::getValor).reversed())
                .toList();
    }

    private List<RelatorioFinanceiroDTO.SerieItem> agregarPorPagamento(List<Pedido> pedidos, BigDecimal totalGeral) {
        Map<String, BigDecimal> rec = new LinkedHashMap<>();
        Map<String, Integer> cnt = new HashMap<>();
        for (Pedido p : pedidos) {
            String fp = p.getFormaPagamento() != null ? p.getFormaPagamento().name() : "OUTRO";
            rec.merge(fp, p.getTotal() != null ? p.getTotal() : BigDecimal.ZERO, BigDecimal::add);
            cnt.merge(fp, 1, Integer::sum);
        }
        return rec.entrySet().stream()
                .map(e -> RelatorioFinanceiroDTO.SerieItem.builder()
                        .label(e.getKey())
                        .valor(e.getValue())
                        .quantidade(cnt.get(e.getKey()))
                        .percentual(percentual(e.getValue(), totalGeral))
                        .build())
                .sorted(Comparator.comparing(RelatorioFinanceiroDTO.SerieItem::getValor).reversed())
                .toList();
    }

    private List<RelatorioFinanceiroDTO.PontoSerie> serieDiaria(
            List<Pedido> pedidos, LocalDate ini, LocalDate fim) {
        // Inicia mapa com TODOS os dias do período (mesmo zerados), pra gráfico ficar contínuo
        Map<String, BigDecimal[]> mapa = new LinkedHashMap<>();
        for (LocalDate d = ini; !d.isAfter(fim); d = d.plusDays(1)) {
            mapa.put(d.toString(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO}); // [valor, qtdPedidos]
        }
        for (Pedido p : pedidos) {
            if (p.getCriadoEm() == null) continue;
            String dia = p.getCriadoEm().toLocalDate().toString();
            BigDecimal[] atual = mapa.get(dia);
            if (atual == null) continue;
            atual[0] = atual[0].add(p.getTotal() != null ? p.getTotal() : BigDecimal.ZERO);
            atual[1] = atual[1].add(BigDecimal.ONE);
        }
        return mapa.entrySet().stream()
                .map(e -> RelatorioFinanceiroDTO.PontoSerie.builder()
                        .data(e.getKey())
                        .faturamento(e.getValue()[0])
                        .pedidos(e.getValue()[1].intValue())
                        .build())
                .toList();
    }

    /** Agrega cada produto: quantidade vendida, receita, custo, margem. */
    private List<RelatorioFinanceiroDTO.ProdutoStat> agregarProdutos(
            List<Pedido> pedidos, Map<Long, BigDecimal> custoPorProduto) {

        // Chave única: id do produto (ou nome se produto deletado)
        Map<String, RelatorioFinanceiroDTO.ProdutoStat> mapa = new HashMap<>();
        for (Pedido p : pedidos) {
            if (p.getItens() == null) continue;
            for (PedidoItem item : p.getItens()) {
                String nome = item.getNomeProduto() != null && !item.getNomeProduto().isBlank()
                        ? item.getNomeProduto()
                        : (item.getProduto() != null ? item.getProduto().getNome() : "Produto removido");
                String chave = item.getProduto() != null ? "id-" + item.getProduto().getId() : "nm-" + nome;

                int qtd = item.getQuantidade() != null ? item.getQuantidade() : 0;
                BigDecimal receita = item.getSubtotal() != null ? item.getSubtotal() : BigDecimal.ZERO;
                BigDecimal custoUnit = item.getProduto() != null
                        ? custoPorProduto.getOrDefault(item.getProduto().getId(), BigDecimal.ZERO)
                        : BigDecimal.ZERO;
                BigDecimal custo = custoUnit.multiply(BigDecimal.valueOf(qtd));

                RelatorioFinanceiroDTO.ProdutoStat atual = mapa.get(chave);
                if (atual == null) {
                    atual = RelatorioFinanceiroDTO.ProdutoStat.builder()
                            .nome(nome).quantidade(0)
                            .receita(BigDecimal.ZERO).custo(BigDecimal.ZERO)
                            .build();
                    mapa.put(chave, atual);
                }
                atual.setQuantidade(atual.getQuantidade() + qtd);
                atual.setReceita(atual.getReceita().add(receita));
                atual.setCusto(atual.getCusto().add(custo));
            }
        }

        // Calcula margem de cada um
        return mapa.values().stream().map(s -> {
            BigDecimal margem = s.getReceita().subtract(s.getCusto());
            s.setMargem(margem.setScale(2, RoundingMode.HALF_UP));
            s.setMargemPercent(percentual(margem, s.getReceita()));
            s.setReceita(s.getReceita().setScale(2, RoundingMode.HALF_UP));
            s.setCusto(s.getCusto().setScale(2, RoundingMode.HALF_UP));
            return s;
        }).toList();
    }

    /**
     * Matriz BCG dos produtos:
     *  - Eixo X (quantidade): acima da mediana = "alta venda"
     *  - Eixo Y (margem %): acima da mediana = "alta margem"
     *
     *      Alta venda    +    Alta margem  = ESTRELA
     *      Alta venda    +    Baixa margem = VACA_LEITEIRA
     *      Baixa venda   +    Alta margem  = DUVIDA
     *      Baixa venda   +    Baixa margem = ABACAXI
     */
    private List<RelatorioFinanceiroDTO.ProdutoBCG> matrizBCG(List<RelatorioFinanceiroDTO.ProdutoStat> stats) {
        if (stats.isEmpty()) return List.of();

        var qtds = stats.stream().map(RelatorioFinanceiroDTO.ProdutoStat::getQuantidade).sorted().toList();
        var margens = stats.stream().map(RelatorioFinanceiroDTO.ProdutoStat::getMargemPercent).sorted().toList();
        double mQtd = mediana(qtds.stream().mapToDouble(Integer::doubleValue).toArray());
        double mMargem = mediana(margens.stream().mapToDouble(b -> b == null ? 0 : b.doubleValue()).toArray());

        List<RelatorioFinanceiroDTO.ProdutoBCG> out = new ArrayList<>();
        for (var s : stats) {
            boolean altaVenda = s.getQuantidade() >= mQtd;
            boolean altaMargem = s.getMargemPercent() != null && s.getMargemPercent().doubleValue() >= mMargem;
            String q;
            if (altaVenda && altaMargem) q = "ESTRELA";
            else if (altaVenda) q = "VACA_LEITEIRA";
            else if (altaMargem) q = "DUVIDA";
            else q = "ABACAXI";
            out.add(RelatorioFinanceiroDTO.ProdutoBCG.builder()
                    .nome(s.getNome())
                    .quantidade(s.getQuantidade())
                    .receita(s.getReceita())
                    .margemPercent(s.getMargemPercent())
                    .quadrante(q)
                    .build());
        }
        return out;
    }

    /**
     * Gera insights textuais baseados nos dados.
     * Heurística simples — sem ML, só comparação e regras de negócio.
     */
    private List<RelatorioFinanceiroDTO.Insight> gerarInsights(
            BigDecimal faturamento, BigDecimal faturamentoAnt,
            int pedidos, int pedidosAnt,
            BigDecimal ticket, BigDecimal ticketAnt,
            BigDecimal margemPct,
            List<RelatorioFinanceiroDTO.SerieItem> porCanal,
            List<RelatorioFinanceiroDTO.ProdutoStat> produtos) {

        List<RelatorioFinanceiroDTO.Insight> ins = new ArrayList<>();

        // 1. Faturamento — comparação com período anterior
        if (faturamentoAnt.signum() > 0) {
            BigDecimal varFat = variacao(faturamento, faturamentoAnt);
            if (varFat.compareTo(BigDecimal.valueOf(10)) >= 0) {
                ins.add(insight("positivo", "trending-up",
                        "Seu faturamento cresceu " + fmt(varFat) + "% comparado ao período anterior. Continue assim!"));
            } else if (varFat.compareTo(BigDecimal.valueOf(-10)) <= 0) {
                ins.add(insight("negativo", "trending-down",
                        "Seu faturamento caiu " + fmt(varFat.abs()) + "% vs período anterior. Vale revisar promoções ou divulgação."));
            }
        }

        // 2. Ticket médio
        if (ticketAnt.signum() > 0) {
            BigDecimal varTk = variacao(ticket, ticketAnt);
            if (varTk.compareTo(BigDecimal.valueOf(5)) >= 0) {
                ins.add(insight("positivo", "trending-up",
                        "Ticket médio subiu " + fmt(varTk) + "% — clientes estão pedindo mais por vez."));
            } else if (varTk.compareTo(BigDecimal.valueOf(-5)) <= 0) {
                ins.add(insight("neutro", "info",
                        "Ticket médio caiu " + fmt(varTk.abs()) + "%. Considere oferecer combos ou upsell de bebidas/sobremesas."));
            }
        }

        // 3. Margem geral
        if (margemPct != null) {
            if (margemPct.compareTo(BigDecimal.valueOf(60)) >= 0) {
                ins.add(insight("positivo", "star",
                        "Margem geral de " + fmt(margemPct) + "% — excelente!"));
            } else if (margemPct.compareTo(BigDecimal.valueOf(30)) < 0 && margemPct.signum() > 0) {
                ins.add(insight("negativo", "alert",
                        "Margem geral de " + fmt(margemPct) + "% está baixa. Considere revisar custos dos insumos ou preços."));
            }
        }

        // 4. Produto mais lucrativo
        var maisLucrativo = produtos.stream()
                .filter(p -> p.getQuantidade() >= 3 && p.getMargemPercent() != null) // só com volume relevante
                .max(Comparator.comparing(RelatorioFinanceiroDTO.ProdutoStat::getMargemPercent))
                .orElse(null);
        if (maisLucrativo != null && maisLucrativo.getMargemPercent().compareTo(BigDecimal.valueOf(50)) >= 0) {
            ins.add(insight("positivo", "star",
                    "\"" + maisLucrativo.getNome() + "\" é seu produto mais lucrativo (margem " + fmt(maisLucrativo.getMargemPercent()) + "%) — destaque ele no cardápio."));
        }

        // 5. Canal mais forte
        if (!porCanal.isEmpty()) {
            var top = porCanal.get(0);
            if (top.getPercentual().compareTo(BigDecimal.valueOf(60)) >= 0) {
                ins.add(insight("neutro", "info",
                        labelCanal(top.getLabel()) + " representa " + fmt(top.getPercentual()) + "% da sua receita — pense em diversificar canais pra reduzir dependência."));
            }
        }

        // 6. Produto sem ficha técnica (CMV não rastreado)
        long semCusto = produtos.stream().filter(p -> p.getCusto().signum() == 0 && p.getReceita().signum() > 0).count();
        if (semCusto >= 3) {
            ins.add(insight("neutro", "alert",
                    semCusto + " produtos não têm ficha técnica cadastrada — você não está calculando o CMV deles. Configure pra ter margem real."));
        }

        // 7. Fallback se não gerou nenhum insight
        if (ins.isEmpty()) {
            ins.add(insight("neutro", "info",
                    "Continue acompanhando seus indicadores. Períodos com mais dados geram insights mais precisos."));
        }

        return ins;
    }

    private RelatorioFinanceiroDTO.Insight insight(String tipo, String icone, String msg) {
        return RelatorioFinanceiroDTO.Insight.builder().tipo(tipo).icone(icone).mensagem(msg).build();
    }

    private String labelCanal(String c) {
        return switch (c) {
            case "DELIVERY" -> "Delivery";
            case "RETIRADA" -> "Retirada";
            case "MESA" -> "Mesa";
            default -> c;
        };
    }

    private BigDecimal percentual(BigDecimal parte, BigDecimal total) {
        if (total == null || total.signum() == 0) return BigDecimal.ZERO;
        return parte.multiply(BigDecimal.valueOf(100))
                .divide(total, 2, RoundingMode.HALF_UP);
    }

    /** Variação % entre dois valores: ((novo - antigo) / antigo) × 100. */
    private BigDecimal variacao(BigDecimal novo, BigDecimal antigo) {
        if (antigo == null || antigo.signum() == 0) {
            return novo != null && novo.signum() > 0 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
        }
        return novo.subtract(antigo)
                .multiply(BigDecimal.valueOf(100))
                .divide(antigo, 2, RoundingMode.HALF_UP);
    }

    private double mediana(double[] valores) {
        if (valores.length == 0) return 0;
        java.util.Arrays.sort(valores);
        int n = valores.length;
        return n % 2 == 1 ? valores[n / 2] : (valores[n / 2 - 1] + valores[n / 2]) / 2.0;
    }

    private String fmt(BigDecimal v) {
        if (v == null) return "0";
        return v.setScale(1, RoundingMode.HALF_UP).toPlainString().replace(".", ",");
    }
}
