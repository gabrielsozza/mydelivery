package com.mydelivery.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.estoque.InsumoDTO;
import com.mydelivery.dto.estoque.MovimentacaoDTO;
import com.mydelivery.dto.estoque.ViabilidadeDTO;
import com.mydelivery.model.FichaTecnicaItem;
import com.mydelivery.model.Insumo;
import com.mydelivery.model.MovimentacaoEstoque;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.PedidoItem;
import com.mydelivery.model.Produto;
import com.mydelivery.repository.FichaTecnicaItemRepository;
import com.mydelivery.repository.InsumoRepository;
import com.mydelivery.repository.MovimentacaoEstoqueRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EstoqueService {

    private final InsumoRepository insumoRepository;
    private final FichaTecnicaItemRepository fichaRepository;
    private final MovimentacaoEstoqueRepository movRepository;
    private final ProdutoRepository produtoRepository;
    private final RestauranteRepository restauranteRepository;

    // ── INSUMOS: CRUD ───────────────────────────────────────────────────────

    public List<InsumoDTO> listarPorRestaurante(Long restauranteId) {
        // Mostra apenas ATIVOS. O DELETE faz soft-delete (setAtivo=false) pra
        // preservar histórico de movimentações, mas a UI deve esconder os
        // inativos — o dono espera que ao excluir o item suma da listagem.
        var insumos = insumoRepository.findByRestauranteIdAndAtivoTrueOrderByNomeAsc(restauranteId);
        // Conta quantos produtos usam cada insumo (1 query agregando em memória)
        var fichas = fichaRepository.findByProdutoRestauranteId(restauranteId);
        Map<Long, Integer> usos = new HashMap<>();
        for (var f : fichas) {
            usos.merge(f.getInsumo().getId(), 1, Integer::sum);
        }
        return insumos.stream().map(i -> {
            InsumoDTO dto = InsumoDTO.fromEntity(i);
            dto.setUsadoEmProdutos(usos.getOrDefault(i.getId(), 0));
            return dto;
        }).collect(Collectors.toList());
    }

    @Transactional
    public InsumoDTO criar(Long restauranteId, InsumoDTO dto) {
        var r = restauranteRepository.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        // saldoAtual começa em ZERO — se dto trouxe valor inicial, o
        // registrarMov abaixo soma 1× (única fonte da verdade). Bug fix
        // jul/2026: antes o builder já setava saldo com o valor DO DTO,
        // e o registrarMov somava de novo — quem digitava "1 unidade"
        // via 2 no estoque final.
        Insumo i = Insumo.builder()
                .restaurante(r)
                .nome(dto.getNome())
                .unidade(parseUnidade(dto.getUnidade()))
                .saldoAtual(BigDecimal.ZERO)
                .saldoMinimo(nz(dto.getSaldoMinimo()))
                .custoMedio(dto.getCustoMedio())
                .observacao(dto.getObservacao())
                .ativo(dto.getAtivo() == null || dto.getAtivo())
                .build();
        i = insumoRepository.save(i);
        // Se cadastra com saldo > 0, registra a ENTRADA_INICIAL — que faz
        // saldo 0 + valor = valor. Audit trail preservado.
        if (nz(dto.getSaldoAtual()).signum() > 0) {
            registrarMov(i, MovimentacaoEstoque.Tipo.ENTRADA_INICIAL,
                    nz(dto.getSaldoAtual()), null, "Saldo inicial");
        }
        return InsumoDTO.fromEntity(i);
    }

    @Transactional
    public InsumoDTO atualizar(Long restauranteId, Long id, InsumoDTO dto) {
        Insumo i = insumoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Insumo não encontrado"));
        if (!i.getRestaurante().getId().equals(restauranteId))
            throw new RuntimeException("Acesso negado");
        if (dto.getNome() != null) i.setNome(dto.getNome());
        if (dto.getUnidade() != null) i.setUnidade(parseUnidade(dto.getUnidade()));
        if (dto.getSaldoMinimo() != null) i.setSaldoMinimo(dto.getSaldoMinimo());
        if (dto.getCustoMedio() != null) i.setCustoMedio(dto.getCustoMedio());
        if (dto.getObservacao() != null) i.setObservacao(dto.getObservacao());
        if (dto.getAtivo() != null) i.setAtivo(dto.getAtivo());
        // Permite editar saldoAtual (jul/2026) — bloquear era feature bem-
        // intencionada de rastreabilidade, mas na prática travava correção
        // de estoque inicial e ajustes de inventário. Restaurante pequeno não
        // roda auditoria contábil sobre isso; se precisar histórico, o
        // futuro endpoint de movimentação continua sendo o caminho. Aqui é
        // o formulário "editar insumo" — quantidade é um dos campos.
        if (dto.getSaldoAtual() != null) i.setSaldoAtual(dto.getSaldoAtual());
        return InsumoDTO.fromEntity(insumoRepository.save(i));
    }

    @Transactional
    public void deletar(Long restauranteId, Long id) {
        Insumo i = insumoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Insumo não encontrado"));
        if (!i.getRestaurante().getId().equals(restauranteId))
            throw new RuntimeException("Acesso negado");
        // Soft delete: marca como inativo. Preserva o histórico de movimentações.
        i.setAtivo(false);
        insumoRepository.save(i);
    }

    // ── MOVIMENTAÇÕES MANUAIS (ajustes) ─────────────────────────────────────

    /**
     * Aplica um ajuste manual (entrada ou saída). A `quantidade` deve ser
     * positiva pra entrada e negativa pra saída — o sinal é mantido na movimentação.
     */
    @Transactional
    public MovimentacaoDTO registrarAjuste(Long restauranteId, Long insumoId,
                                           BigDecimal quantidade, String observacao) {
        Insumo i = insumoRepository.findById(insumoId)
                .orElseThrow(() -> new RuntimeException("Insumo não encontrado"));
        if (!i.getRestaurante().getId().equals(restauranteId))
            throw new RuntimeException("Acesso negado");
        if (quantidade == null || quantidade.signum() == 0)
            throw new RuntimeException("Quantidade do ajuste não pode ser zero");

        var mov = registrarMov(i, MovimentacaoEstoque.Tipo.AJUSTE, quantidade, null,
                observacao != null ? observacao : "Ajuste manual");
        return MovimentacaoDTO.fromEntity(mov);
    }

    /**
     * Registra uma PERDA com motivo categorizado.
     * Recebe quantidade positiva — internamente vira movimentação negativa do tipo PERDA.
     */
    @Transactional
    public MovimentacaoDTO registrarPerda(Long restauranteId, Long insumoId,
                                          BigDecimal quantidade, MovimentacaoEstoque.MotivoPerda motivo,
                                          String observacao) {
        Insumo i = insumoRepository.findById(insumoId)
                .orElseThrow(() -> new RuntimeException("Insumo não encontrado"));
        if (!i.getRestaurante().getId().equals(restauranteId))
            throw new RuntimeException("Acesso negado");
        if (quantidade == null || quantidade.signum() <= 0)
            throw new RuntimeException("Quantidade da perda precisa ser positiva");
        if (motivo == null) motivo = MovimentacaoEstoque.MotivoPerda.OUTRO;

        // PERDA sempre debita do estoque (quantidade negativa)
        BigDecimal qtdNeg = quantidade.negate();
        BigDecimal saldoAntes = nz(i.getSaldoAtual());
        BigDecimal saldoDepois = saldoAntes.add(qtdNeg);
        i.setSaldoAtual(saldoDepois);
        insumoRepository.save(i);

        MovimentacaoEstoque m = MovimentacaoEstoque.builder()
                .insumo(i)
                .tipo(MovimentacaoEstoque.Tipo.PERDA)
                .quantidade(qtdNeg)
                .saldoApos(saldoDepois)
                .motivoPerda(motivo)
                .observacao(observacao != null && !observacao.isBlank()
                        ? "Perda (" + motivo + "): " + observacao
                        : "Perda — " + motivo)
                .build();
        m = movRepository.save(m);
        log.info("🗑️ Perda registrada: {} {} de {} (motivo: {})",
                quantidade, i.getUnidade(), i.getNome(), motivo);
        return MovimentacaoDTO.fromEntity(m);
    }

    public List<MovimentacaoDTO> listarMovimentacoes(Long restauranteId) {
        return movRepository.findByInsumoRestauranteIdOrderByCriadoEmDesc(restauranteId)
                .stream().map(MovimentacaoDTO::fromEntity).collect(Collectors.toList());
    }

    public List<MovimentacaoDTO> listarMovimentacoesDoInsumo(Long restauranteId, Long insumoId) {
        Insumo i = insumoRepository.findById(insumoId)
                .orElseThrow(() -> new RuntimeException("Insumo não encontrado"));
        if (!i.getRestaurante().getId().equals(restauranteId))
            throw new RuntimeException("Acesso negado");
        return movRepository.findByInsumoIdOrderByCriadoEmDesc(insumoId)
                .stream().map(MovimentacaoDTO::fromEntity).collect(Collectors.toList());
    }

    // ── BAIXA AUTOMÁTICA POR PEDIDO ─────────────────────────────────────────

    /**
     * Chamado pelo PedidoService logo após salvar um pedido.
     * Pra cada item do pedido, encontra a ficha técnica e desconta os insumos.
     * Se um insumo não tem ficha, é ignorado (não obrigatório cadastrar tudo).
     *
     * NÃO bloqueia se o saldo ficar negativo — apenas registra. O alerta vermelho
     * no frontend mostra a ruptura. (Comportamento explícito: o restaurante
     * pode aceitar pedidos mesmo sem estoque registrado e ajustar depois.)
     */
    @Transactional
    public void baixarEstoquePorPedido(Pedido pedido) {
        if (pedido == null || pedido.getItens() == null || pedido.getItens().isEmpty()) return;

        for (PedidoItem it : pedido.getItens()) {
            if (it.getProduto() == null || it.getQuantidade() == null) continue;
            Long produtoId = it.getProduto().getId();
            int qtdVendida = it.getQuantidade();

            List<FichaTecnicaItem> ficha = fichaRepository.findByProdutoId(produtoId);
            if (ficha.isEmpty()) continue; // sem ficha cadastrada → não baixa

            for (FichaTecnicaItem fi : ficha) {
                var insumo = fi.getInsumo();
                if (insumo == null) continue;
                // Converte o consumo pra unidade do insumo antes de descontar.
                // Ex: insumo em LITROS, receita em ML(300) → 300ml × 5 vendas
                // = 1500ml = 1.5 L pra debitar do saldo (que está em L).
                // Sem essa conversão, o sistema debitaria 1500 LITROS,
                // zerando saldo errado e disparando ruptura falsa.
                var unInsumo = insumo.getUnidade();
                var unReceita = fi.getUnidadeReceita() != null
                        ? fi.getUnidadeReceita()
                        : unInsumo;
                BigDecimal consumoPorUnidade = com.mydelivery.util.UnidadeConversor
                        .converter(fi.getQuantidade(), unReceita, unInsumo);
                BigDecimal consumo = consumoPorUnidade.multiply(BigDecimal.valueOf(qtdVendida));
                // SAIDA_VENDA é registrada com quantidade NEGATIVA
                registrarMov(insumo, MovimentacaoEstoque.Tipo.SAIDA_VENDA,
                        consumo.negate(), pedido.getId(),
                        "Venda do pedido #" + pedido.getId() + " (" + qtdVendida + "x " + it.getProduto().getNome() + ")");
            }
        }
    }

    // ── VIABILIDADE (a inovação) ────────────────────────────────────────────

    /**
     * Pra cada produto do restaurante que tem ficha técnica, calcula quantas
     * unidades dá pra produzir com o estoque atual.
     *
     * Fórmula: unidades_produzíveis(produto) = MIN sobre cada item da ficha de
     *   FLOOR(insumo.saldoAtual / item.quantidade).
     *
     * Se algum insumo tem saldo 0, o produto está em RUPTURA (= 0 unidades).
     */
    public ViabilidadeDTO.Resumo calcularViabilidade(Long restauranteId) {
        var fichas = fichaRepository.findByProdutoRestauranteId(restauranteId);
        var insumos = insumoRepository.findByRestauranteIdOrderByNomeAsc(restauranteId);

        // Agrupa fichas por produto
        Map<Long, List<FichaTecnicaItem>> fichasPorProduto = fichas.stream()
                .collect(Collectors.groupingBy(f -> f.getProduto().getId()));

        // Pega TODOS os produtos do restaurante (mesmo os sem ficha — pra mostrar info)
        List<Produto> produtos = produtoRepository.findByRestauranteId(restauranteId);

        List<ViabilidadeDTO> result = new java.util.ArrayList<>();
        int emRuptura = 0;

        for (Produto p : produtos) {
            if (p.getRestaurante() == null || !p.getRestaurante().getId().equals(restauranteId)) continue;
            var itens = fichasPorProduto.get(p.getId());
            if (itens == null || itens.isEmpty()) {
                result.add(ViabilidadeDTO.semFicha(p.getId(), p.getNome()));
                continue;
            }
            int minUnidades = Integer.MAX_VALUE;
            String gargalo = null;
            for (FichaTecnicaItem fi : itens) {
                var insumo = fi.getInsumo();
                if (insumo == null) continue;
                BigDecimal porUnit = fi.getQuantidade();
                if (porUnit == null || porUnit.signum() <= 0) continue;

                // CRÍTICO: converter saldo e consumo pra mesma unidade BASE
                // antes de dividir. Sem isso, insumo em LITRO consumido em ML
                // (300) calcularia 10/300 = 0 (ruptura falsa). Agora:
                // 10L = 10000ml ÷ 300ml = 33 unidades.
                var unInsumo = insumo.getUnidade();
                var unReceita = fi.getUnidadeReceita() != null
                        ? fi.getUnidadeReceita()
                        : unInsumo; // legado: receita assume unidade do insumo
                BigDecimal saldoBase = com.mydelivery.util.UnidadeConversor
                        .paraBase(insumo.getSaldoAtual(), unInsumo);
                BigDecimal consumoBase = com.mydelivery.util.UnidadeConversor
                        .paraBase(porUnit, unReceita);
                if (consumoBase == null || consumoBase.signum() <= 0) continue;

                int possiveis = saldoBase == null || saldoBase.signum() <= 0
                        ? 0
                        : saldoBase.divide(consumoBase, 0, RoundingMode.FLOOR).intValue();
                if (possiveis < minUnidades) {
                    minUnidades = possiveis;
                    gargalo = insumo.getNome();
                }
            }
            if (minUnidades == Integer.MAX_VALUE) minUnidades = 0;
            boolean ruptura = minUnidades == 0;
            if (ruptura) emRuptura++;
            result.add(ViabilidadeDTO.builder()
                    .produtoId(p.getId())
                    .produtoNome(p.getNome())
                    .unidadesProduziveis(minUnidades)
                    .insumoLimitante(gargalo)
                    .ruptura(ruptura)
                    .build());
        }

        int baixos = (int) insumos.stream()
                .filter(i -> Boolean.TRUE.equals(i.getAtivo()))
                .filter(i -> {
                    if (i.getSaldoAtual() == null) return true;
                    if (i.getSaldoMinimo() == null) return false;
                    return i.getSaldoAtual().compareTo(i.getSaldoMinimo()) <= 0;
                })
                .count();

        return ViabilidadeDTO.Resumo.builder()
                .produtos(result)
                .totalEmRuptura(emRuptura)
                .totalInsumosBaixos(baixos)
                .build();
    }

    // ── RELATÓRIO AGREGADO ──────────────────────────────────────────────────

    /**
     * Resumo de movimentações num período. Calcula:
     *  - Valor total comprado (somando ENTRADA_COMPRA × custoUnitário, via observação não — usa custoMedio do insumo)
     *  - Valor consumido (SAIDA_VENDA × custoMedio)
     *  - Valor perdido (PERDA × custoMedio)
     *  - Top 5 insumos mais consumidos
     *  - Top 5 insumos com maior valor de perda
     *  - Série diária de consumo (pra gráfico)
     */
    public com.mydelivery.dto.estoque.RelatorioEstoqueDTO relatorio(
            Long restauranteId, java.time.LocalDateTime ini, java.time.LocalDateTime fim) {

        var movs = movRepository.findByInsumoRestauranteIdAndCriadoEmBetweenOrderByCriadoEmDesc(
                restauranteId, ini, fim);

        BigDecimal valComprado = BigDecimal.ZERO;
        BigDecimal valConsumido = BigDecimal.ZERO;
        BigDecimal valPerdido = BigDecimal.ZERO;
        int totalCompras = 0, totalVendas = 0, totalPerdas = 0;

        // Agregadores
        Map<Long, BigDecimal> qtdConsumidoPorInsumo = new HashMap<>();
        Map<Long, BigDecimal> qtdPerdidaPorInsumo = new HashMap<>();
        Map<Long, com.mydelivery.model.Insumo> insumosMap = new HashMap<>();
        Map<String, BigDecimal> consumoPorDia = new java.util.LinkedHashMap<>();

        for (var m : movs) {
            if (m.getInsumo() == null) continue;
            var ins = m.getInsumo();
            insumosMap.put(ins.getId(), ins);
            BigDecimal custo = ins.getCustoMedio() != null ? ins.getCustoMedio() : BigDecimal.ZERO;
            BigDecimal qtdAbs = m.getQuantidade() != null ? m.getQuantidade().abs() : BigDecimal.ZERO;
            BigDecimal valor = qtdAbs.multiply(custo).setScale(2, java.math.RoundingMode.HALF_UP);

            switch (m.getTipo()) {
                case ENTRADA_COMPRA -> { valComprado = valComprado.add(valor); totalCompras++; }
                case SAIDA_VENDA -> {
                    valConsumido = valConsumido.add(valor);
                    totalVendas++;
                    qtdConsumidoPorInsumo.merge(ins.getId(), qtdAbs, BigDecimal::add);
                    // Série diária
                    String dia = m.getCriadoEm().toLocalDate().toString();
                    consumoPorDia.merge(dia, valor, BigDecimal::add);
                }
                case PERDA -> {
                    valPerdido = valPerdido.add(valor);
                    totalPerdas++;
                    qtdPerdidaPorInsumo.merge(ins.getId(), qtdAbs, BigDecimal::add);
                }
                default -> {} // outros tipos não entram no resumo
            }
        }

        // Top 5 consumidos
        var topConsumidos = qtdConsumidoPorInsumo.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(5)
                .map(e -> {
                    var ins = insumosMap.get(e.getKey());
                    BigDecimal custo = ins.getCustoMedio() != null ? ins.getCustoMedio() : BigDecimal.ZERO;
                    return com.mydelivery.dto.estoque.RelatorioEstoqueDTO.ItemRanking.builder()
                            .insumoNome(ins.getNome())
                            .unidade(ins.getUnidade() != null ? ins.getUnidade().name() : "UN")
                            .quantidade(e.getValue())
                            .valor(e.getValue().multiply(custo).setScale(2, java.math.RoundingMode.HALF_UP))
                            .build();
                }).toList();

        // Top 5 perdas
        var topPerdas = qtdPerdidaPorInsumo.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(5)
                .map(e -> {
                    var ins = insumosMap.get(e.getKey());
                    BigDecimal custo = ins.getCustoMedio() != null ? ins.getCustoMedio() : BigDecimal.ZERO;
                    return com.mydelivery.dto.estoque.RelatorioEstoqueDTO.ItemRanking.builder()
                            .insumoNome(ins.getNome())
                            .unidade(ins.getUnidade() != null ? ins.getUnidade().name() : "UN")
                            .quantidade(e.getValue())
                            .valor(e.getValue().multiply(custo).setScale(2, java.math.RoundingMode.HALF_UP))
                            .build();
                }).toList();

        // Série de consumo diário — ordenada por data
        var serie = consumoPorDia.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> com.mydelivery.dto.estoque.RelatorioEstoqueDTO.PontoSerie.builder()
                        .data(e.getKey()).valor(e.getValue()).build())
                .toList();

        return com.mydelivery.dto.estoque.RelatorioEstoqueDTO.builder()
                .dataInicio(ini.toLocalDate().toString())
                .dataFim(fim.toLocalDate().toString())
                .valorComprado(valComprado)
                .valorConsumido(valConsumido)
                .valorPerdido(valPerdido)
                .totalCompras(totalCompras)
                .totalVendas(totalVendas)
                .totalPerdas(totalPerdas)
                .topConsumidos(topConsumidos)
                .topPerdas(topPerdas)
                .consumoDiario(serie)
                .build();
    }

    // ── HELPERS PRIVADOS ────────────────────────────────────────────────────

    private MovimentacaoEstoque registrarMov(Insumo i, MovimentacaoEstoque.Tipo tipo,
                                             BigDecimal quantidade, Long pedidoId, String obs) {
        BigDecimal saldoAntes = nz(i.getSaldoAtual());
        BigDecimal saldoDepois = saldoAntes.add(quantidade); // quantidade pode ser negativa
        i.setSaldoAtual(saldoDepois);
        insumoRepository.save(i);

        MovimentacaoEstoque m = MovimentacaoEstoque.builder()
                .insumo(i)
                .tipo(tipo)
                .quantidade(quantidade)
                .saldoApos(saldoDepois)
                .pedidoId(pedidoId)
                .observacao(obs)
                .build();
        m = movRepository.save(m);
        log.debug("📦 Estoque: {} {} {} (saldo agora: {})",
                tipo, quantidade, i.getNome(), saldoDepois);
        return m;
    }

    private Insumo.Unidade parseUnidade(String s) {
        if (s == null) return Insumo.Unidade.UN;
        try { return Insumo.Unidade.valueOf(s.trim().toUpperCase()); }
        catch (Exception e) { return Insumo.Unidade.UN; }
    }

    private BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
}
