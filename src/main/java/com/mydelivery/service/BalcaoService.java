package com.mydelivery.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.Cliente;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.PedidoItem;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.model.SenhaBalcao;
import com.mydelivery.repository.ClienteRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.SenhaBalcaoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Lógica do POS de balcão.
 *
 * Responsabilidades:
 *  - Gerar senha diária sequencial pra cada pedido.
 *  - Criar pedido tipo=BALCAO já em CONFIRMADO (vai direto pra cozinha).
 *  - Reconhecer cliente recorrente pelo telefone (memória entre visitas).
 *  - Listar fila ativa pro caixa.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalcaoService {

    private final PedidoRepository pedidoRepo;
    private final ProdutoRepository produtoRepo;
    private final SenhaBalcaoRepository senhaRepo;
    private final ClienteRepository clienteRepo;

    /**
     * Cria pedido de balcão. Itens vêm validados (id+qtd). Total recalculado
     * server-side. Gera senha sequencial diária. Vincula cliente se telefone
     * informado e já existir.
     *
     * @return Map com pedidoId, senhaNumero, total — pra o caixa imprimir
     */
    @Transactional
    public Map<String, Object> criarPedido(Restaurante r, String nomeChamada,
                                            String telefoneCliente,
                                            List<Map<String, Object>> itensReq,
                                            String observacao,
                                            String formaPagamentoStr) {
        if (itensReq == null || itensReq.isEmpty()) {
            throw new IllegalArgumentException("itens vazio");
        }
        // Nome obrigatorio — pedido sem identificacao salvava com nome generico
        // "cliente", o que confundia operacao do balcao (varios pedidos iguais).
        if (nomeChamada == null || nomeChamada.trim().isEmpty()) {
            throw new IllegalArgumentException("Informe o nome do cliente.");
        }

        Pedido p = new Pedido();
        p.setRestaurante(r);
        p.setTipo(Pedido.Tipo.BALCAO);
        p.setStatus(Pedido.Status.CONFIRMADO);
        Pedido.FormaPagamento fp = parseFormaPag(formaPagamentoStr);
        p.setFormaPagamento(fp);
        p.setModoPagamento(Pedido.ModoPagamento.NA_ENTREGA);
        // Se o caixa JÁ escolheu a forma de pagamento real (Dinheiro/PIX/Cartão),
        // o cliente está pagando AGORA — marca pago=true e dispensa o botão
        // "Cobrar" no painel. Só fica pendente quando o caixa explicitamente
        // marca "Cobrar depois" (forma=PENDENTE) — aí o /cobrar endpoint
        // resolve depois.
        if (fp != Pedido.FormaPagamento.PENDENTE) {
            p.setPago(true);
            p.setPagoEm(java.time.LocalDateTime.now());
        }
        p.setNomeChamada(nomeChamada.trim());
        p.setObservacao(observacao);
        p.setTaxaEntrega(BigDecimal.ZERO);
        p.setDesconto(BigDecimal.ZERO);

        // Cliente: se telefone bate com cadastro, vincula. Senão NÃO cria
        // automaticamente — balcão é rápido, não vamos cadastrar todo
        // mundo. Só usa quem JÁ é cliente (memória entre visitas).
        if (telefoneCliente != null && !telefoneCliente.isBlank()) {
            String tel = telefoneCliente.replaceAll("\\D", "");
            if (tel.length() >= 10) {
                clienteRepo.findByTelefoneAndRestauranteId(tel, r.getId())
                        .ifPresent(p::setCliente);
            }
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        List<PedidoItem> itens = new ArrayList<>();
        for (var it : itensReq) {
            Long pid = toLong(it.get("produtoId"));
            int qtd = Math.max(1, toInt(it.get("quantidade"), 1));
            String obsItem = strOf(it.get("observacao"));
            Produto prod = produtoRepo.findById(pid)
                    .orElseThrow(() -> new IllegalArgumentException("Produto " + pid + " inválido"));
            if (!prod.getRestaurante().getId().equals(r.getId())) {
                throw new SecurityException("Produto fora do restaurante");
            }
            BigDecimal precoUnit = prod.getPreco() == null ? BigDecimal.ZERO : prod.getPreco();
            BigDecimal totalItem = precoUnit.multiply(BigDecimal.valueOf(qtd));
            subtotal = subtotal.add(totalItem);

            PedidoItem pi = new PedidoItem();
            pi.setPedido(p);
            pi.setProduto(prod);
            pi.setNomeProduto(prod.getNome());
            pi.setQuantidade(qtd);
            pi.setPrecoUnitario(precoUnit);
            pi.setSubtotal(totalItem);
            pi.setObservacao(obsItem);
            itens.add(pi);
        }
        p.setSubtotal(subtotal);
        p.setTotal(subtotal);
        p.setItens(itens);

        Pedido salvo = pedidoRepo.save(p);

        // Gera senha sequencial diária
        SenhaBalcao senha = gerarSenha(r.getId(), salvo.getId(), nomeChamada);

        log.info("[Balcao] Pedido #{} senha={} cliente={} total=R${}",
                salvo.getId(), senha.getNumero(), nomeChamada, subtotal);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pedidoId", salvo.getId());
        out.put("senhaNumero", senha.getNumero());
        out.put("nomeChamada", nomeChamada);
        out.put("total", salvo.getTotal());
        out.put("formaPagamento", fp.name());
        return out;
    }

    private SenhaBalcao gerarSenha(Long restauranteId, Long pedidoId, String nomeChamada) {
        LocalDate hoje = LocalDate.now();
        int proximo = senhaRepo.findFirstByRestauranteIdAndDataEmissaoOrderByNumeroDesc(restauranteId, hoje)
                .map(s -> s.getNumero() + 1)
                .orElse(1);
        SenhaBalcao senha = SenhaBalcao.builder()
                .restauranteId(restauranteId)
                .numero(proximo)
                .dataEmissao(hoje)
                .pedidoId(pedidoId)
                .nomeCliente(nomeChamada)
                .build();
        return senhaRepo.save(senha);
    }

    /**
     * Memória do cliente: dado um telefone, devolve "Costuma pedir: X (3×), Y (2×)"
     * pra mostrar ao caixa. Útil pra sugerir o "usual" em 1 toque.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> memoriaCliente(Long restauranteId, String telefone) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (telefone == null || telefone.isBlank()) {
            out.put("encontrado", false); return out;
        }
        String tel = telefone.replaceAll("\\D", "");
        if (tel.length() < 10) { out.put("encontrado", false); return out; }
        Cliente c = clienteRepo.findByTelefoneAndRestauranteId(tel, restauranteId).orElse(null);
        if (c == null) { out.put("encontrado", false); return out; }
        out.put("encontrado", true);
        out.put("clienteId", c.getId());
        out.put("nome", c.getNome());
        // Top 5 produtos mais pedidos por esse cliente
        var pedidos = pedidoRepo.findByRestauranteIdOrderByCriadoEmDesc(restauranteId);
        Map<Long, int[]> contagem = new HashMap<>(); // produtoId → [qtd_pedidos, qtd_unidades]
        Map<Long, String> nomes = new HashMap<>();
        int totalPedidos = 0;
        for (var p : pedidos) {
            if (p.getCliente() == null || !c.getId().equals(p.getCliente().getId())) continue;
            totalPedidos++;
            for (var it : p.getItens()) {
                if (it.getProduto() == null) continue;
                Long pid = it.getProduto().getId();
                contagem.computeIfAbsent(pid, k -> new int[2]);
                contagem.get(pid)[0]++;
                contagem.get(pid)[1] += it.getQuantidade() == null ? 1 : it.getQuantidade();
                nomes.put(pid, it.getNomeProduto());
            }
            if (totalPedidos >= 20) break; // últimos 20 pedidos é representativo
        }
        List<Map<String, Object>> top = contagem.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("produtoId", e.getKey());
                    m.put("nome", nomes.get(e.getKey()));
                    m.put("vezesPedido", e.getValue()[0]);
                    return m;
                }).toList();
        out.put("totalPedidos", totalPedidos);
        out.put("costumaPedir", top);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> fila(Long restauranteId) {
        // Pedidos BALCAO de HOJE não-finalizados, ordem cronológica
        var todos = pedidoRepo.findByRestauranteIdOrderByCriadoEmDesc(restauranteId);
        var hoje = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var p : todos) {
            if (p.getTipo() != Pedido.Tipo.BALCAO) continue;
            if (p.getCriadoEm() == null) continue;
            if (!p.getCriadoEm().toLocalDate().equals(hoje)) continue;
            if (p.getStatus() == Pedido.Status.ENTREGUE || p.getStatus() == Pedido.Status.CANCELADO) continue;
            var senha = senhaRepo.findByPedidoId(p.getId()).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pedidoId", p.getId());
            m.put("senha", senha == null ? null : senha.getNumero());
            m.put("nomeChamada", p.getNomeChamada());
            m.put("status", p.getStatus().name());
            m.put("total", p.getTotal());
            m.put("criadoEm", p.getCriadoEm().toString());
            m.put("itens", p.getItens().stream().map(it -> Map.of(
                    "nome", it.getNomeProduto(),
                    "quantidade", it.getQuantidade()
            )).toList());
            out.add(m);
        }
        // Mais antigo primeiro (FIFO)
        out.sort((a, b) -> ((String) a.get("criadoEm")).compareTo((String) b.get("criadoEm")));
        return out;
    }

    private Pedido.FormaPagamento parseFormaPag(String s) {
        if (s == null || s.trim().isEmpty()) return Pedido.FormaPagamento.DINHEIRO;
        try { return Pedido.FormaPagamento.valueOf(s.trim().toUpperCase()); }
        catch (Exception e) { return Pedido.FormaPagamento.DINHEIRO; }
    }

    /**
     * Cobranca tardia: pedido foi criado com forma PENDENTE ("cobrar depois"),
     * agora o cliente esta pagando. Atualiza forma + marca pago=true.
     * Nao muda o status (CONFIRMADO continua CONFIRMADO; ENTREGUE continua
     * ENTREGUE) — quem move status e' o endpoint PATCH dedicado.
     */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> cobrar(Long restauranteId, Long pedidoId, String formaPagamentoStr) {
        Pedido p = pedidoRepo.findById(pedidoId)
                .orElseThrow(() -> new IllegalArgumentException("Pedido " + pedidoId + " nao existe"));
        if (!p.getRestaurante().getId().equals(restauranteId)) {
            throw new SecurityException("Pedido de outro restaurante");
        }
        Pedido.FormaPagamento fp = parseFormaPag(formaPagamentoStr);
        if (fp == Pedido.FormaPagamento.PENDENTE) {
            throw new IllegalArgumentException("Escolha a forma de pagamento real.");
        }
        p.setFormaPagamento(fp);
        p.setPago(true);
        p.setPagoEm(java.time.LocalDateTime.now());
        pedidoRepo.save(p);
        return Map.of("ok", true, "formaPagamento", fp.name(), "pago", true);
    }
    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }
    private int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return def; }
    }
    private String strOf(Object o) { return o == null ? null : o.toString(); }
}
