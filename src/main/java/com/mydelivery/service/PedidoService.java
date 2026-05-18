package com.mydelivery.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.pedido.AtribuirEntregadorRequest;
import com.mydelivery.dto.pedido.AtualizarStatusRequest;
import com.mydelivery.dto.pedido.EditarPedidoRequest;
import com.mydelivery.dto.pedido.NovoPedidoRequest;
import com.mydelivery.dto.pedido.PedidoResponse;
import com.mydelivery.model.Cliente;
import com.mydelivery.model.Entregador;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.PedidoItem;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.ClienteRepository;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;
import com.mydelivery.repository.EntregadorRepository;
import com.mydelivery.repository.MesaRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PedidoService {

    private final PedidoRepository pedidoRepository;
    private final RestauranteRepository restauranteRepository;
    private final ProdutoRepository produtoRepository;
    private final ClienteRepository clienteRepository;
    private final ConfiguracaoRestauranteRepository configuracaoRepository;
    private final EntregadorRepository entregadorRepository;
    private final MesaRepository mesaRepository;
    private final CarrinhoAbandonadoService carrinhoAbandonadoService;
    private final CupomService cupomService;
    private final FidelidadeService fidelidadeService;
    private final com.mydelivery.repository.CupomRepository cupomRepository;
    private final EstoqueService estoqueService;
    private final PagamentoService pagamentoService;

    @Transactional
    public PedidoResponse criarPedido(NovoPedidoRequest request) {
        Restaurante restaurante = restauranteRepository.findBySlug(request.getSlug())
                .orElseThrow(() -> new RuntimeException("Restaurante nao encontrado"));
        Cliente cliente = null;
        if (request.getCliente() != null && request.getCliente().getTelefone() != null) {
            // Sempre normaliza: só dígitos. Garante que telefones com/sem máscara
            // vindos de diferentes pontos do sistema apontem pro mesmo cliente.
            String telefone = com.mydelivery.util.TelefoneUtil.normalizar(request.getCliente().getTelefone());
            String nome = request.getCliente().getNome();
            cliente = clienteRepository.findByTelefoneAndRestauranteId(telefone, restaurante.getId())
                    .orElseGet(() -> {
                        Cliente c = new Cliente();
                        c.setRestaurante(restaurante);
                        c.setNome(nome);
                        c.setTelefone(telefone);
                        return clienteRepository.save(c);
                    });
            if (nome != null) {
                cliente.setNome(nome);
                clienteRepository.save(cliente);
            }
        }
        // ── Taxa de entrega (só DELIVERY) ──
        // Mesa e retirada não cobram taxa. Delivery faz lookup por bairro.
        BigDecimal taxaEntrega = BigDecimal.ZERO;
        if ("delivery".equalsIgnoreCase(request.getModo())) {
            String bairroCliente = request.getEndereco() == null ? null
                    : request.getEndereco().getOrDefault("bairro", null);
            if (bairroCliente == null || bairroCliente.isBlank()) {
                throw new RuntimeException("Informe o bairro de entrega.");
            }
            taxaEntrega = buscarTaxaPorBairro(restaurante, bairroCliente);
            if (taxaEntrega == null) {
                throw new RuntimeException("Desculpe, nossa loja ainda não entrega nessa região.");
            }
        }

        // ── Mesa (presencial) ──
        // Resolve a mesa via slug que vem no endereço, e o nome do cliente que
        // assina a comanda. Não criamos registro em Cliente (mesa não tem telefone).
        com.mydelivery.model.Mesa mesa = null;
        String nomeClienteMesa = null;
        if ("mesa".equalsIgnoreCase(request.getModo())) {
            String mesaSlug = request.getEndereco() == null ? null
                    : request.getEndereco().getOrDefault("mesa", null);
            if (mesaSlug == null || mesaSlug.isBlank()) {
                throw new RuntimeException("Mesa não identificada.");
            }
            mesa = mesaRepository.findByRestauranteIdAndSlug(restaurante.getId(), mesaSlug)
                    .orElseThrow(() -> new RuntimeException("Mesa não encontrada."));
            nomeClienteMesa = request.getCliente() != null ? request.getCliente().getNome() : null;
            if (nomeClienteMesa == null || nomeClienteMesa.isBlank()) {
                throw new RuntimeException("Informe seu nome pra abrir a comanda da mesa.");
            }
            // Para pedido de mesa NÃO criamos/buscamos cliente — fica null no Pedido.
            // O nome digitado fica em nome_cliente_mesa pra agrupar comandas.
            cliente = null;
        }
        Pedido.Tipo tipo = switch (request.getModo().toLowerCase()) {
            case "delivery" ->
                Pedido.Tipo.DELIVERY;
            case "retirada" ->
                Pedido.Tipo.RETIRADA;
            case "mesa" ->
                Pedido.Tipo.MESA;
            default ->
                throw new RuntimeException("Modo invalido");
        };
        Pedido.FormaPagamento fp = switch (request.getPagamento().toLowerCase()) {
            case "pix" ->
                Pedido.FormaPagamento.PIX;
            case "credito" ->
                Pedido.FormaPagamento.CARTAO_CREDITO;
            case "debito" ->
                // Cartão de débito disponível APENAS na entrega (maquininha).
                // Débito online não é mais aceito.
                Pedido.FormaPagamento.CARTAO_DEBITO;
            case "dinheiro" ->
                Pedido.FormaPagamento.DINHEIRO;
            // APPLE_PAY mantido no enum por compatibilidade com pedidos antigos
            // no banco, mas não é mais aceito como entrada nova.
            default ->
                Pedido.FormaPagamento.CARTAO_MAQUININHA;
        };
        // ONLINE = paga agora pelo site (PIX/cartão de crédito)
        // NA_ENTREGA = paga ao receber (dinheiro/pix/crédito/débito na maquininha)
        Pedido.ModoPagamento modoPagamento = "online".equalsIgnoreCase(request.getModoPagamento())
                ? Pedido.ModoPagamento.ONLINE
                : Pedido.ModoPagamento.NA_ENTREGA;
        String enderecoStr = null;
        if (request.getEndereco() != null && !request.getEndereco().isEmpty()) {
            if ("delivery".equalsIgnoreCase(request.getModo())) {
                enderecoStr = request.getEndereco().getOrDefault("rua", "") + ", " + request.getEndereco().getOrDefault("numero", "") + " - " + request.getEndereco().getOrDefault("bairro", "");
            } else if ("mesa".equalsIgnoreCase(request.getModo())) {
                // Mesa real (mesa != null) → usa nome cadastrado. Senão fallback no que veio.
                enderecoStr = (mesa != null ? mesa.getNome() : ("Mesa " + request.getEndereco().getOrDefault("mesa", "")))
                            + (nomeClienteMesa != null ? " · " + nomeClienteMesa : "");
            }
        }
        Pedido pedido = new Pedido();
        pedido.setRestaurante(restaurante);
        pedido.setCliente(cliente);
        pedido.setMesa(mesa);
        pedido.setNomeClienteMesa(nomeClienteMesa);
        pedido.setTipo(tipo);
        pedido.setFormaPagamento(fp);
        pedido.setModoPagamento(modoPagamento);
        pedido.setEnderecoEntrega(enderecoStr);
        pedido.setObservacao(request.getTroco() != null ? "Troco para: " + request.getTroco() : null);
        pedido.setTaxaEntrega(taxaEntrega);
        // Agendamento opcional: se vier preenchido e for futuro, o pedido fica marcado como agendado
        boolean agendado = request.getAgendadoPara() != null
                && request.getAgendadoPara().isAfter(java.time.LocalDateTime.now());
        if (agendado) {
            pedido.setAgendadoPara(request.getAgendadoPara());
        }
        // ── Status inicial ──
        // ONLINE → AGUARDANDO_PAGAMENTO até o cliente pagar
        // NA_ENTREGA → PENDENTE (ou CONFIRMADO se auto-aceitar e não-agendado)
        // Pedido pago automaticamente vira CONFIRMADO via PagamentoService.confirmar()
        boolean autoAceitar = Boolean.TRUE.equals(restaurante.getAceitarPedidosAutomaticamente()) && !agendado;
        Pedido.Status statusInicial;
        if (modoPagamento == Pedido.ModoPagamento.ONLINE) {
            statusInicial = Pedido.Status.AGUARDANDO_PAGAMENTO;
        } else {
            statusInicial = autoAceitar ? Pedido.Status.CONFIRMADO : Pedido.Status.PENDENTE;
        }
        pedido.setStatus(statusInicial);
        List<PedidoItem> itens = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (NovoPedidoRequest.ItemDto itemReq : request.getItens()) {
            Produto produto = produtoRepository.findById(itemReq.getProdutoId())
                    .orElseThrow(() -> new RuntimeException("Produto nao encontrado: " + itemReq.getProdutoId()));
            BigDecimal precoUnit = produto.getPreco();
            BigDecimal itemSub = precoUnit.multiply(BigDecimal.valueOf(itemReq.getQty()));
            PedidoItem item = new PedidoItem();
            item.setPedido(pedido);
            item.setProduto(produto);
            // Snapshot do nome — sobrevive a exclusão futura do produto
            item.setNomeProduto(produto.getNome());
            item.setQuantidade(itemReq.getQty());
            item.setPrecoUnitario(precoUnit);
            item.setSubtotal(itemSub);
            item.setObservacao(itemReq.getObs());
            itens.add(item);
            subtotal = subtotal.add(itemSub);
        }
        pedido.setItens(itens);
        pedido.setSubtotal(subtotal);

        // ── Aplicação de cupom ─────────────────────────────────────────────
        BigDecimal desconto = BigDecimal.ZERO;
        com.mydelivery.model.Cupom cupomAplicado = null;
        if (request.getCupomCodigo() != null && !request.getCupomCodigo().isBlank()) {
            String tel = cliente != null ? cliente.getTelefone() : null;
            var validReq = new com.mydelivery.dto.cupom.ValidarCupomRequest();
            validReq.setCodigo(request.getCupomCodigo());
            validReq.setSlug(restaurante.getSlug());
            validReq.setTelefone(tel);
            validReq.setSubtotal(subtotal);
            validReq.setModo(request.getModo());
            var resp = cupomService.validar(validReq);
            if (resp.isValido()) {
                desconto = resp.getDesconto() != null ? resp.getDesconto() : BigDecimal.ZERO;
                cupomAplicado = cupomRepository
                        .findByCodigoIgnoreCaseAndRestauranteSlug(resp.getCodigo(), restaurante.getSlug())
                        .orElse(null);
                pedido.setCupomCodigo(resp.getCodigo());
                pedido.setDesconto(desconto);
            }
        }

        // Total = subtotal − desconto + taxa (desconto não pode passar do subtotal)
        BigDecimal totalCalc = subtotal.subtract(desconto).max(BigDecimal.ZERO).add(taxaEntrega);
        pedido.setTotal(totalCalc);

        // Agendamento opcional já está setado acima — só pra deixar claro
        Pedido salvo = pedidoRepository.save(pedido);

        // ── Pagamento ──────────────────────────────────────────────────────
        // Cria o Pagamento associado. Pra PIX online, gera o BR Code aqui mesmo.
        // Pra cartão/applepay online, fica como placeholder (gateway plugaria aqui).
        // Pra NA_ENTREGA, só registra o método escolhido pra histórico.
        try {
            pagamentoService.criarOuObter(salvo);
        } catch (Exception e) {
            // Não bloqueia o pedido se falhar (ex: chave PIX não cadastrada)
        }

        // ── Registra uso do cupom ──────────────────────────────────────────
        if (cupomAplicado != null && cliente != null) {
            try {
                cupomService.registrarUso(cupomAplicado, salvo.getId(), cliente.getTelefone(), desconto);
            } catch (Exception e) {
                // não bloqueia o pedido se registrar uso falhar
            }
        }

        // ── Crédito de pontos de fidelidade ────────────────────────────────
        // Base de cálculo: subtotal (sem taxa, sem desconto) — incentiva consumo,
        // não a taxa de entrega.
        try {
            if (cliente != null && cliente.getTelefone() != null) {
                fidelidadeService.creditarPorPedido(
                        restaurante, cliente.getTelefone(), salvo.getId(), subtotal);
                // Guarda o saldo atual de pontos no pedido pra exibição no admin
                int saldoAtual = fidelidadeService.calcularSaldo(
                        restaurante.getId(), cliente.getTelefone());
                salvo.setPontosGanhos(saldoAtual);
                pedidoRepository.save(salvo);
            }
        } catch (Exception ignored) {}

        // Marca todos os carrinhos abandonados desse cliente como RECUPERADO.
        try {
            if (cliente != null && cliente.getTelefone() != null) {
                carrinhoAbandonadoService.marcarRecuperadoPorTelefone(
                        restaurante.getId(), cliente.getTelefone());
            }
        } catch (Exception ignored) {}

        // ── Baixa automática de estoque ────────────────────────────────────
        // Pra cada item, consulta a ficha técnica e desconta os insumos.
        // Se um produto não tem ficha, é ignorado (não obrigatório).
        // Não bloqueia o pedido se um insumo zerar — registra e segue.
        try {
            estoqueService.baixarEstoquePorPedido(salvo);
        } catch (Exception ignored) {}

        return toResponse(salvo);
    }

    @Transactional(readOnly = true)
    public List<PedidoResponse> listarPedidos(Long rid) {
        return pedidoRepository.findByRestauranteIdOrderByCriadoEmDesc(rid).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PedidoResponse> listarPorStatus(Long rid, Pedido.Status status) {
        return pedidoRepository.findByRestauranteIdAndStatusOrderByCriadoEmDesc(rid, status).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PedidoResponse buscarPorId(Long rid, Long pid) {
        Pedido p = pedidoRepository.findById(pid).orElseThrow(() -> new RuntimeException("Pedido nao encontrado"));
        if (!p.getRestaurante().getId().equals(rid)) {
            throw new RuntimeException("Acesso negado");
        }
        return toResponse(p);
    }

    @Transactional
    public PedidoResponse atualizarStatus(Long rid, Long pid, AtualizarStatusRequest req) {
        Pedido p = pedidoRepository.findById(pid).orElseThrow(() -> new RuntimeException("Pedido nao encontrado"));
        if (!p.getRestaurante().getId().equals(rid)) {
            throw new RuntimeException("Acesso negado");
        }
        p.setStatus(req.getStatus());
        return toResponse(pedidoRepository.save(p));
    }

    @Transactional
    public PedidoResponse editarPedido(Long rid, Long pid, EditarPedidoRequest req) {
        Pedido p = pedidoRepository.findById(pid)
                .orElseThrow(() -> new RuntimeException("Pedido nao encontrado"));
        if (!p.getRestaurante().getId().equals(rid)) {
            throw new RuntimeException("Acesso negado");
        }

        p.getItens().clear();
        List<PedidoItem> novos = new ArrayList<>();
        BigDecimal sub = BigDecimal.ZERO;

        for (EditarPedidoRequest.ItemDto ir : req.getItens()) {
            BigDecimal pu = ir.getPrecoUnitario() != null ? ir.getPrecoUnitario() : BigDecimal.ZERO;
            int qty = ir.getQuantidade() != null ? ir.getQuantidade() : 1;
            BigDecimal is = pu.multiply(BigDecimal.valueOf(qty));

            PedidoItem item = new PedidoItem();
            item.setPedido(p);
            item.setQuantidade(qty);
            item.setPrecoUnitario(pu);
            item.setSubtotal(is);
            item.setObservacao(ir.getObservacao());

            if (ir.getProdutoId() != null) {
                Produto prod = produtoRepository.findById(ir.getProdutoId())
                        .orElseThrow(() -> new RuntimeException("Produto nao encontrado: " + ir.getProdutoId()));
                item.setProduto(prod);
                item.setNomeProduto(prod.getNome()); // snapshot
            } else if (ir.getNomeProduto() != null) {
                item.setNomeProduto(ir.getNomeProduto()); // snapshot direto
                produtoRepository.findFirstByNome(ir.getNomeProduto())
                        .ifPresent(item::setProduto);
            }

            novos.add(item);
            sub = sub.add(is);
        }

        p.getItens().addAll(novos);
        p.setSubtotal(sub);
        p.setTotal(sub.add(p.getTaxaEntrega() != null ? p.getTaxaEntrega() : BigDecimal.ZERO));
        if (req.getObservacao() != null) {
            p.setObservacao(req.getObservacao());
        }

        return toResponse(pedidoRepository.save(p));
    }

    @Transactional
    public PedidoResponse atribuirEntregador(Long rid, Long pid, AtribuirEntregadorRequest req) {
        Pedido p = pedidoRepository.findById(pid).orElseThrow(() -> new RuntimeException("Pedido nao encontrado"));
        if (!p.getRestaurante().getId().equals(rid)) {
            throw new RuntimeException("Acesso negado");
        }
        if (req.getEntregadorId() == null) {
            p.setEntregador(null);
        } else {
            Entregador e = entregadorRepository.findById(req.getEntregadorId())
                    .orElseThrow(() -> new RuntimeException("Entregador nao encontrado"));
            p.setEntregador(e);
            e.setStatus(Entregador.Status.EM_ENTREGA);
        }
        return toResponse(pedidoRepository.save(p));
    }

    @Transactional(readOnly = true)
    public PedidoResponse acompanharPedido(Long pid) {
        return toResponse(pedidoRepository.findById(pid).orElseThrow(() -> new RuntimeException("Pedido nao encontrado")));
    }

    private PedidoResponse toResponse(Pedido p) {
        // Nome do produto vem do snapshot (i.getNomeProduto). Fallback pra produto.getNome()
        // só pra pedidos antigos sem snapshot. Se ambos nulos, "Produto removido".
        List<PedidoResponse.ItemPedidoResponse> itens = p.getItens() == null ? List.of()
                : p.getItens().stream().map(i -> {
                    String nome = i.getNomeProduto();
                    if ((nome == null || nome.isBlank()) && i.getProduto() != null) {
                        nome = i.getProduto().getNome();
                    }
                    if (nome == null) nome = "Produto removido";
                    return PedidoResponse.ItemPedidoResponse.builder()
                        .id(i.getId()).nomeProduto(nome).quantidade(i.getQuantidade())
                        .precoUnitario(i.getPrecoUnitario()).subtotal(i.getSubtotal()).observacao(i.getObservacao()).build();
                }).toList();
        return PedidoResponse.builder().id(p.getId()).status(p.getStatus()).tipo(p.getTipo())
                .formaPagamento(p.getFormaPagamento())
                .modoPagamento(p.getModoPagamento())
                .pago(p.getPago())
                .pagoEm(p.getPagoEm())
                .nomeCliente(p.getCliente() != null ? p.getCliente().getNome() : null)
                .telefoneCliente(p.getCliente() != null ? p.getCliente().getTelefone() : null)
                .enderecoEntrega(p.getEnderecoEntrega()).observacao(p.getObservacao())
                .subtotal(p.getSubtotal()).taxaEntrega(p.getTaxaEntrega()).total(p.getTotal())
                .criadoEm(p.getCriadoEm())
                .agendadoPara(p.getAgendadoPara())
                .restauranteNome(p.getRestaurante() != null ? p.getRestaurante().getNome() : null)
                .entregadorId(p.getEntregador() != null ? p.getEntregador().getId() : null)
                .entregadorNome(p.getEntregador() != null ? p.getEntregador().getNome() : null)
                .itens(itens).build();
    }

    /**
     * Busca a taxa configurada pro bairro informado pelo cliente.
     * Match tolerante: case + acento + substring (assim "Eldorado" casa
     * mesmo se cadastrado como "Eldorado - Serra").
     *
     * @return taxa em BigDecimal (zero se cadastrado mas sem taxa setada),
     *         ou null se o bairro NÃO está na lista de atendimento.
     */
    private BigDecimal buscarTaxaPorBairro(Restaurante r, String bairroCliente) {
        if (r.getBairrosAtendidos() == null || r.getBairrosAtendidos().isEmpty()) return null;
        String alvo = normalizarBairro(bairroCliente);
        return r.getBairrosAtendidos().stream()
                .filter(b -> b != null && b.getNome() != null)
                .filter(b -> {
                    String n = normalizarBairro(b.getNome());
                    return n.contains(alvo) || alvo.contains(n);
                })
                .findFirst()
                .map(b -> b.getTaxa() != null ? b.getTaxa() : BigDecimal.ZERO)
                .orElse(null);
    }

    private static String normalizarBairro(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase().trim();
    }
}
