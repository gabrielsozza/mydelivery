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
import lombok.extern.slf4j.Slf4j;

@Slf4j
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
    private final com.mydelivery.repository.MesaSessaoRepository mesaSessaoRepository;
    private final CarrinhoAbandonadoService carrinhoAbandonadoService;
    private final CupomService cupomService;
    private final FidelidadeService fidelidadeService;
    private final com.mydelivery.repository.CupomRepository cupomRepository;
    private final EstoqueService estoqueService;
    private final PagamentoService pagamentoService;
    private final HorarioLojaService horarioLojaService;
    private final com.mydelivery.service.ifood.IfoodClient ifoodClient;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WebPushService webPushService;

    /** Opcional pra não quebrar testes. Usado pra mandar link de
     *  acompanhamento pro cliente via WhatsApp logo após pedido criado. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.mydelivery.service.whatsapp.WhatsappBotService whatsappBotService;

    @Transactional
    public PedidoResponse criarPedido(NovoPedidoRequest request) {
        Restaurante restaurante = restauranteRepository.findBySlug(request.getSlug())
                .orElseThrow(() -> new RuntimeException("Restaurante nao encontrado"));

        // Validação de aceitação de pedidos:
        //  - Se a loja está manualmente fechada (aberto=false), recusa.
        //  - Se está no "cutoff" (N min antes do fechamento com toggle ativo), recusa.
        //  - Pedidos agendados (agendadoPara no futuro) são SEMPRE aceitos — a loja
        //    deveria estar aberta no momento marcado, não agora.
        boolean ehAgendado = request.getAgendadoPara() != null
                && request.getAgendadoPara().isAfter(java.time.LocalDateTime.now());
        if (!ehAgendado) {
            if (!Boolean.TRUE.equals(restaurante.getAberto())) {
                throw new RuntimeException("Loja fechada no momento. Tente mais tarde.");
            }
            var estado = horarioLojaService.calcular(restaurante);
            if (estado.dentroCutoff) {
                throw new RuntimeException("Estamos prestes a fechar — não estamos mais aceitando pedidos novos.");
            }
        }

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
            // ── Atualiza ÚLTIMO endereço estruturado do cliente ──────────
            // Só pra DELIVERY (mesa/retirada não tem endereço de entrega).
            // Permite pré-preencher checkout em pedidos futuros e reduzir
            // atrito ("já sei seu endereço, escolha o pagamento").
            if ("delivery".equalsIgnoreCase(request.getModo())
                    && request.getEndereco() != null && !request.getEndereco().isEmpty()) {
                var end = request.getEndereco();
                cliente.setEnderecoRua(end.getOrDefault("rua", null));
                cliente.setEnderecoNumero(end.getOrDefault("numero", null));
                cliente.setEnderecoComplemento(end.getOrDefault("complemento", null));
                cliente.setEnderecoBairro(end.getOrDefault("bairro", null));
                cliente.setEnderecoReferencia(end.getOrDefault("referencia", null));
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
                // Monta endereço incluindo COMPLEMENTO (Ap, bloco, casa) e REFERÊNCIA
                // (próximo a tal coisa) que o cliente preenche. Bug anterior:
                // só rua/numero/bairro eram concatenados — entregador não enxergava
                // complemento na comanda e errava entrega.
                String rua  = request.getEndereco().getOrDefault("rua", "");
                String num  = request.getEndereco().getOrDefault("numero", "");
                String comp = request.getEndereco().getOrDefault("complemento", "");
                String bai  = request.getEndereco().getOrDefault("bairro", "");
                String ref  = request.getEndereco().getOrDefault("referencia", "");
                StringBuilder sb = new StringBuilder();
                if (rua != null && !rua.isBlank()) sb.append(rua);
                if (num != null && !num.isBlank()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(num);
                }
                if (comp != null && !comp.isBlank()) {
                    if (sb.length() > 0) sb.append(" - ");
                    sb.append(comp);
                }
                if (bai != null && !bai.isBlank()) {
                    if (sb.length() > 0) sb.append(" - ");
                    sb.append(bai);
                }
                if (ref != null && !ref.isBlank()) {
                    if (sb.length() > 0) sb.append(" (Ref: ").append(ref).append(")");
                    else sb.append("Ref: ").append(ref);
                }
                enderecoStr = sb.toString();
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
            // Preço base do produto (do banco). É o piso — cliente nunca paga menos.
            BigDecimal precoUnit = produto.getPreco();

            // ── BLINDAGEM DE COBRANÇA DE COMPLEMENTOS ──
            // Duas camadas, independentes (cinto + suspensório):
            //
            //  1. PRIMÁRIA: se o frontend mandou `preco` MAIOR que o base, é
            //     porque já somou o complemento no cliente (ex.: Açaí R$ 27 +
            //     Frozen R$ 3 = R$ 30). Usa esse valor.
            //
            //  2. FALLBACK: se o frontend NÃO mandou preço (versão antiga do
            //     HTML cacheada no browser do cliente, ou Netlify atrasado),
            //     extrai os valores dos complementos pagos da própria obs.
            //     A obs vem no formato "+ Leite em pó, Granola, Frozen (R$ 3,00)".
            //     Regex captura todos os "(R$ X,XX)" e soma.
            //
            //  Sanity check anti-fraude: nunca aceita preço menor que o base,
            //  nunca aceita complemento > 10× o base (cliente malicioso).
            //
            // CASO ESPECIAL — produto com preço VITRINE (kg, porção variável):
            //  o preço base é referencial, não cobrado. O cliente paga só o
            //  valor das porções escolhidas. Aqui usamos o `preco` vindo do
            //  frontend (que já somou os complementos) com limite mais aberto:
            //  100x do preço base. Ex: feijão R$ 59,99/kg, cliente pega 250g
            //  = R$ 15 (menor que base, ainda válido pra vitrine).
            boolean ehVitrine = Boolean.TRUE.equals(produto.getPrecoVitrine());
            BigDecimal limiteMax = precoUnit.multiply(BigDecimal.valueOf(ehVitrine ? 100 : 10));
            if (ehVitrine) {
                // Vitrine: preço cobrado vem 100% do frontend (porção escolhida).
                // Não soma com base. Aceita qualquer valor > 0 e <= limiteMax.
                BigDecimal pf = itemReq.getPreco();
                if (pf != null && pf.compareTo(BigDecimal.ZERO) > 0
                        && pf.compareTo(limiteMax) <= 0) {
                    precoUnit = pf;
                } else {
                    // Fallback: extrai dos complementos na obs.
                    BigDecimal extras = extrairValorComplementosDaObs(itemReq.getObs());
                    precoUnit = extras.compareTo(limiteMax) <= 0 ? extras : limiteMax;
                }
            } else if (itemReq.getPreco() != null
                    && itemReq.getPreco().compareTo(precoUnit) > 0
                    && itemReq.getPreco().compareTo(limiteMax) <= 0) {
                precoUnit = itemReq.getPreco();
            } else {
                BigDecimal extras = extrairValorComplementosDaObs(itemReq.getObs());
                if (extras.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal somado = precoUnit.add(extras);
                    if (somado.compareTo(limiteMax) <= 0) precoUnit = somado;
                }
            }
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

        // ── Web Push: notifica TODOS aparelhos do restaurante (toca mesmo
        //    com tela bloqueada se Service Worker registrado). Best-effort.
        if (webPushService != null) {
            try {
                String tipoPedido = String.valueOf(salvo.getTipo());
                boolean ehMesa = "MESA".equalsIgnoreCase(tipoPedido);
                String titulo = ehMesa ? "🍽️ Novo pedido — Mesa" : "🛵 Novo pedido — Delivery";
                String corpo = (salvo.getCliente() != null && salvo.getCliente().getNome() != null
                                ? salvo.getCliente().getNome() + " · " : "")
                             + "R$ " + (salvo.getTotal() == null ? "0,00"
                                : salvo.getTotal().toPlainString().replace(".", ","));
                String url = ehMesa ? "/pedidos.html?tipo=mesa" : "/pedidos.html?tipo=delivery";
                webPushService.notificar(restaurante.getId(), titulo, corpo, url,
                        ehMesa ? "pedido-mesa" : "pedido-delivery");
            } catch (Exception ignored) {}
        }

        // ── WhatsApp: link de acompanhamento pro cliente ──
        // Só DELIVERY/RETIRADA (mesa/balcão é presencial). Async + fail-safe
        // — NUNCA propaga erro pra criação do pedido. 8 salvaguardas anti-
        // shadowban implementadas dentro do método.
        if (whatsappBotService != null && cliente != null) {
            try {
                String tipoPed = salvo.getTipo() == null ? null : salvo.getTipo().name();
                whatsappBotService.notificarLinkAcompanhamentoAsync(
                        restaurante, salvo.getId(), tipoPed, cliente.getTelefone());
            } catch (Exception ignored) { /* fail-safe total */ }
        }

        // ── Atribuição automática de entregador (DELIVERY) ───────────────
        // Round-robin por carga real: escolhe o entregador DISPONIVEL com
        // menos pedidos ativos no momento. Empate → ordem de criação (estável).
        // Restaurante pode reatribuir manual depois pelo painel.
        // Fail-safe: se não houver entregador disponível, pedido fica sem
        // entregador (dono atribui manualmente). Não bloqueia criação.
        if (salvo.getTipo() == Pedido.Tipo.DELIVERY && salvo.getEntregador() == null) {
            try {
                atribuirAutomaticamente(salvo);
            } catch (Exception ignored) { /* fail-safe total */ }
        }

        return toResponse(salvo);
    }

    /**
     * Round-robin por carga. Pega entregadores DISPONIVEL do restaurante,
     * conta pedidos ativos (CONFIRMADO/EM_PREPARO/PRONTO/SAIU_ENTREGA) por
     * entregador, escolhe quem tem menos. Persiste atribuição.
     *
     * Decisão: NÃO muda status do entregador pra EM_ENTREGA aqui — só
     * quando ele efetivamente sair pra entrega (transição manual no app
     * ou painel). Pedido CONFIRMADO/EM_PREPARO ainda tá na cozinha, faz
     * sentido entregador continuar DISPONIVEL pra receber próxima atribuição
     * (entregador real do iFood/Anota carrega 2-3 pedidos juntos).
     */
    private void atribuirAutomaticamente(Pedido pedido) {
        Long restId = pedido.getRestaurante().getId();
        List<Entregador> disponiveis = entregadorRepository
                .findByRestauranteIdAndAtivoTrueAndStatus(restId, Entregador.Status.DISPONIVEL);
        if (disponiveis.isEmpty()) return;

        // Conta pedidos ativos por entregador num único pass — DELIVERY rate
        // não é alto o suficiente pra justificar query agregada custom.
        java.util.Map<Long, Long> cargaPorEntregador = pedidoRepository
                .findByRestauranteIdOrderByCriadoEmDesc(restId).stream()
                .filter(p -> p.getEntregador() != null
                        && p.getStatus() != Pedido.Status.ENTREGUE
                        && p.getStatus() != Pedido.Status.CANCELADO)
                .collect(java.util.stream.Collectors.groupingBy(
                        p -> p.getEntregador().getId(), java.util.stream.Collectors.counting()));

        Entregador escolhido = disponiveis.stream()
                .min(java.util.Comparator
                        .comparingLong((Entregador e) -> cargaPorEntregador.getOrDefault(e.getId(), 0L))
                        .thenComparing(Entregador::getId))
                .orElse(null);
        if (escolhido == null) return;

        pedido.setEntregador(escolhido);
        pedidoRepository.save(pedido);
        log.info("[entregador] auto-atribuído pedidoId={} -> entregadorId={} ({}) carga={}",
                pedido.getId(), escolhido.getId(), escolhido.getNome(),
                cargaPorEntregador.getOrDefault(escolhido.getId(), 0L));
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
        Pedido.Status statusAntigo = p.getStatus();
        Pedido.Status statusNovo = req.getStatus();
        p.setStatus(statusNovo);
        Pedido salvo = pedidoRepository.save(p);

        // ── PROPAGAÇÃO PRO IFOOD ──────────────────────────────────────────
        // Pedidos vindos do iFood (origem=IFOOD) precisam que o restaurante
        // notifique a Order API quando avança o status, senão o iFood deixa
        // o pedido travado em "criado mas não confirmado" — exatamente o que
        // fez 3 cenários de homologação falharem (Confirmado, Despachado,
        // Cancelado). Mapeamento:
        //
        //   nosso CONFIRMADO/EM_PREPARO   → POST /orders/{id}/confirm
        //   nosso SAIU_ENTREGA            → POST /orders/{id}/dispatch
        //   nosso CANCELADO               → POST /orders/{id}/cancel (motivo 501)
        //
        // Try/catch envolve cada chamada — falha de rede no iFood NÃO bloqueia
        // a transição local. Erro só fica em log pra debug. ifoodOrderId é o
        // UUID que o iFood usa internamente (vem do webhook PLC e fica salvo
        // na coluna ifood_order_id quando o pedido foi importado pelo polling).
        if (salvo.getOrigem() == Pedido.Origem.IFOOD
                && salvo.getIfoodOrderId() != null
                && !salvo.getIfoodOrderId().isBlank()
                && statusNovo != statusAntigo) {
            String oid = salvo.getIfoodOrderId();
            try {
                if (statusNovo == Pedido.Status.CONFIRMADO) {
                    // Confirm é idempotente — iFood aceita mesmo se já confirmado.
                    ifoodClient.confirmar(oid);
                    log.info("[iFood] confirm enviado pra orderId={}", oid);
                } else if (statusNovo == Pedido.Status.EM_PREPARO) {
                    // Garante confirm primeiro (caso pulou do PENDENTE direto
                    // pra EM_PREPARO sem passar por CONFIRMADO) e depois manda
                    // o startPreparation — alguns testes de homologação verificam
                    // esse evento separadamente. Ambos são idempotentes.
                    try { ifoodClient.confirmar(oid); } catch (Exception ignored) {}
                    ifoodClient.emPreparo(oid);
                    log.info("[iFood] startPreparation enviado pra orderId={}", oid);
                } else if (statusNovo == Pedido.Status.PRONTO) {
                    // readyToPickup é OBRIGATÓRIO pra logística MERCHANT (entregador
                    // próprio do restaurante). Pra logística iFood, é opcional
                    // mas idempotente — não causa erro.
                    ifoodClient.pronto(oid);
                    log.info("[iFood] readyToPickup enviado pra orderId={}", oid);
                } else if (statusNovo == Pedido.Status.SAIU_ENTREGA) {
                    // Sem bloqueio por agendamento: o iFood considera
                    // legítimo o restaurante despachar quando quiser, mesmo
                    // pra pedidos com deliveryDateTime futuro. Bloquear aqui
                    // reprovou homologação cenário "Despachado Imediato"
                    // pq o TOQAN tratava como imediato pedidos com janela.
                    ifoodClient.despachado(oid);
                    log.info("[iFood] dispatch enviado pra orderId={}", oid);
                } else if (statusNovo == Pedido.Status.ENTREGUE) {
                    // /delivered marca CONCLUDED na Order API. Pra logística
                    // iFood o ifoodClient.entregue() engole 400/409 (iFood já
                    // gera CON sozinho). Pra logística MERCHANT, sobe o status.
                    ifoodClient.entregue(oid);
                    log.info("[iFood] delivered enviado pra orderId={}", oid);
                } else if (statusNovo == Pedido.Status.CANCELADO) {
                    // Código 501 = "OUT_OF_PRODUCT" (motivo padrão).
                    ifoodClient.cancelar(oid, "501", "Cancelado pelo restaurante via painel");
                    log.info("[iFood] cancel enviado pra orderId={}", oid);
                }
            } catch (Exception e) {
                log.error("[iFood] FALHOU ao propagar status {} pra orderId={}: {}",
                        statusNovo, oid, e.getMessage());
            }
        }

        return toResponse(salvo);
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

    /**
     * Cliente cancela um item da própria comanda. Validações:
     *  - pedido pertence à mesa do slug
     *  - nome bate (não dá pra cancelar item de outra pessoa da mesa)
     *  - status permite (PENDENTE ou CONFIRMADO; depois de EM_PREPARO já era)
     *
     * Se removeu o último item, o pedido inteiro vira CANCELADO.
     * Total/subtotal recalculados in-place.
     */
    @Transactional
    public void cancelarItemComanda(String slugRestaurante, String slugMesa,
                                     Long pedidoId, Long itemId, String nomePessoa) {
        var mesa = mesaRepository.findByRestauranteSlugAndSlug(slugRestaurante, slugMesa)
                .orElseThrow(() -> new RuntimeException("Mesa não encontrada"));
        var pedido = pedidoRepository.findById(pedidoId)
                .orElseThrow(() -> new RuntimeException("Pedido não encontrado"));

        if (pedido.getMesa() == null || !pedido.getMesa().getId().equals(mesa.getId())) {
            throw new RuntimeException("Esse pedido não é dessa mesa.");
        }
        if (nomePessoa == null || nomePessoa.isBlank()
                || pedido.getNomeClienteMesa() == null
                || !normalizarBairro(pedido.getNomeClienteMesa()).equals(normalizarBairro(nomePessoa))) {
            throw new RuntimeException("Esse pedido não está em sua comanda.");
        }
        var status = pedido.getStatus();
        if (status == Pedido.Status.EM_PREPARO
                || status == Pedido.Status.SAIU_ENTREGA
                || status == Pedido.Status.ENTREGUE
                || status == Pedido.Status.CANCELADO) {
            throw new RuntimeException("Esse item já foi pra cozinha e não pode ser cancelado.");
        }

        var alvo = pedido.getItens().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Item não encontrado."));
        pedido.getItens().remove(alvo);

        if (pedido.getItens().isEmpty()) {
            // Sem itens → cancela o pedido inteiro
            pedido.setStatus(Pedido.Status.CANCELADO);
            pedido.setSubtotal(BigDecimal.ZERO);
            pedido.setTotal(BigDecimal.ZERO);
        } else {
            BigDecimal sub = pedido.getItens().stream()
                    .map(i -> i.getSubtotal() != null ? i.getSubtotal() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            pedido.setSubtotal(sub);
            // Mesa não tem taxa de entrega; total = subtotal
            pedido.setTotal(sub.add(pedido.getTaxaEntrega() != null ? pedido.getTaxaEntrega() : BigDecimal.ZERO));
        }
        pedidoRepository.save(pedido);
    }

    /**
     * Fecha a comanda inteira da mesa (todos os pedidos ativos viram ENTREGUE+pago).
     * Usado pelo dono ao receber o pagamento final no balcão.
     */
    @Transactional
    public int fecharComandaMesa(Long restauranteId, Long mesaId) {
        var mesa = mesaRepository.findById(mesaId)
                .orElseThrow(() -> new RuntimeException("Mesa não encontrada"));
        if (!mesa.getRestaurante().getId().equals(restauranteId)) {
            throw new RuntimeException("Mesa de outro restaurante.");
        }
        var ativos = pedidoRepository.findComandaAtivaPorMesa(mesaId);
        var agora = java.time.LocalDateTime.now();
        ativos.forEach(p -> {
            p.setStatus(Pedido.Status.ENTREGUE);
            p.setPago(true);
            if (p.getPagoEm() == null) p.setPagoEm(agora);
        });
        pedidoRepository.saveAll(ativos);
        return ativos.size();
    }

    /**
     * Marca a comanda como PAGA sem fechar/entregar — útil quando o cliente
     * já pagou mas a comida ainda está sendo entregue/finalizada. Não muda
     * status (continua PENDENTE/CONFIRMADO/EM_PREPARO), só seta pago=true.
     */
    @Transactional
    public int marcarComandaPaga(Long restauranteId, Long mesaId) {
        var mesa = mesaRepository.findById(mesaId)
                .orElseThrow(() -> new RuntimeException("Mesa não encontrada"));
        if (!mesa.getRestaurante().getId().equals(restauranteId)) {
            throw new RuntimeException("Mesa de outro restaurante.");
        }
        var ativos = pedidoRepository.findComandaAtivaPorMesa(mesaId);
        var agora = java.time.LocalDateTime.now();
        int alterados = 0;
        for (var p : ativos) {
            if (Boolean.TRUE.equals(p.getPago())) continue; // já estava pago
            p.setPago(true);
            if (p.getPagoEm() == null) p.setPagoEm(agora);
            alterados++;
        }
        if (alterados > 0) pedidoRepository.saveAll(ativos);
        return alterados;
    }

    /**
     * Comanda ATIVA da mesa pra cliente acompanhar (público). Se nome informado,
     * filtra só os pedidos daquela pessoa — assim Maria não vê o que João pediu.
     * Se nome vazio, retorna todos os pedidos ativos (uso pela cozinha).
     */
    @Transactional(readOnly = true)
    public List<PedidoResponse> comandaDaMesa(String slugRestaurante, String slugMesa, String nomePessoa) {
        var mesa = mesaRepository.findByRestauranteSlugAndSlug(slugRestaurante, slugMesa)
                .orElseThrow(() -> new RuntimeException("Mesa não encontrada"));
        var pedidos = pedidoRepository.findComandaAtivaPorMesa(mesa.getId());
        if (nomePessoa != null && !nomePessoa.isBlank()) {
            String alvo = normalizarBairro(nomePessoa); // mesmo normalizador (lowercase+sem-acento)
            pedidos = pedidos.stream()
                    .filter(p -> p.getNomeClienteMesa() != null
                            && normalizarBairro(p.getNomeClienteMesa()).equals(alvo))
                    .toList();
        }
        return pedidos.stream().map(this::toResponse).toList();
    }

    private PedidoResponse toResponse(Pedido p) {
        // ─────────────────────────────────────────────────────────────────
        // CORREÇÃO DE COBRANÇA NA EXIBIÇÃO (defesa de última camada).
        //
        // Pedidos antigos foram criados quando o backend ainda não somava
        // os complementos. Ficaram com precoUnitario = preço base do
        // produto e subtotal = base × qty. A obs traz "(R$ 3,00)" no nome
        // do complemento, mas o valor não foi somado.
        //
        // Em vez de fazer migration no banco (arriscado, irreversível),
        // ajustamos na hora de devolver o pedido: detectamos a inconsistência
        // (precoUnitario × qty == subtotal salvo, mas obs tem complemento
        // pago) e devolvemos os valores corrigidos. O pedido no banco
        // permanece como está; o painel passa a mostrar o valor correto.
        //
        // É idempotente: se o pedido JÁ tem o valor com complemento somado
        // (precoUnitario > base, ou subtotal já reflete o ajuste),
        // não faz nada.
        // ─────────────────────────────────────────────────────────────────
        BigDecimal subtotalCorrigido = BigDecimal.ZERO;
        boolean houveAjuste = false;

        List<PedidoResponse.ItemPedidoResponse> itens = new java.util.ArrayList<>();
        if (p.getItens() != null) {
            for (var i : p.getItens()) {
                String nome = i.getNomeProduto();
                if ((nome == null || nome.isBlank()) && i.getProduto() != null) {
                    nome = i.getProduto().getNome();
                }
                if (nome == null) nome = "Produto removido";

                BigDecimal precoUnit = i.getPrecoUnitario() == null ? BigDecimal.ZERO : i.getPrecoUnitario();
                int qtd = i.getQuantidade() == null ? 1 : i.getQuantidade();
                BigDecimal subSalvo = i.getSubtotal() == null ? BigDecimal.ZERO : i.getSubtotal();

                BigDecimal precoFinal = precoUnit;
                BigDecimal subFinal = subSalvo;

                // ── Heurística para detectar pedidos antigos (cobrados a menos) ──
                // Compara precoUnitario SALVO com o preço BASE do produto:
                //   precoUnit > base  → complemento ja foi somado na criacao,
                //                        nao mexer (caso #179 e #181 atuais)
                //   precoUnit == base → cobrado SEM complemento. Se a obs traz
                //                        "(R$ X)", soma agora (pedido antigo
                //                        corrigido na exibicao)
                //   produto == null   → produto deletado, sem como comparar,
                //                        nao mexer
                //
                // Isso elimina a duplicacao que existia na versao anterior:
                // antes a heuristica olhava so subSalvo vs subEsperadoComExtras,
                // que era SEMPRE verdadeiro quando havia extras na obs
                // (subSalvo de R\$ 25 < R\$ 28 esperado → adicionava de novo).
                BigDecimal precoBase = null;
                try {
                    if (i.getProduto() != null && i.getProduto().getPreco() != null) {
                        precoBase = i.getProduto().getPreco();
                    }
                } catch (Exception ignore) {
                    // LazyInitException em algum caso bizarro — deixa null,
                    // cai no caminho "nao mexer" abaixo
                }

                if (precoBase != null && precoUnit.compareTo(precoBase) <= 0) {
                    // precoUnit nao excede a base → cobrado sem complemento.
                    BigDecimal extras = extrairValorComplementosDaObs(i.getObservacao());
                    if (extras.compareTo(BigDecimal.ZERO) > 0) {
                        precoFinal = precoBase.add(extras);
                        subFinal = precoFinal.multiply(BigDecimal.valueOf(qtd));
                        houveAjuste = true;
                    }
                }

                subtotalCorrigido = subtotalCorrigido.add(subFinal);

                itens.add(PedidoResponse.ItemPedidoResponse.builder()
                        .id(i.getId()).nomeProduto(nome).quantidade(qtd)
                        .precoUnitario(precoFinal).subtotal(subFinal).observacao(i.getObservacao()).build());
            }
        }

        // Subtotal/total finais — usa o corrigido se houve ajuste, senão o salvo.
        BigDecimal subtotalOut = houveAjuste ? subtotalCorrigido : p.getSubtotal();
        BigDecimal totalOut;
        if (houveAjuste) {
            // total = subtotal corrigido − desconto + taxa, sem deixar negativo.
            BigDecimal desconto = p.getDesconto() == null ? BigDecimal.ZERO : p.getDesconto();
            BigDecimal taxa = p.getTaxaEntrega() == null ? BigDecimal.ZERO : p.getTaxaEntrega();
            totalOut = subtotalCorrigido.subtract(desconto).max(BigDecimal.ZERO).add(taxa);
        } else {
            totalOut = p.getTotal();
        }

        return PedidoResponse.builder().id(p.getId()).status(p.getStatus()).tipo(p.getTipo())
                .formaPagamento(p.getFormaPagamento())
                .modoPagamento(p.getModoPagamento())
                .pago(p.getPago())
                .pagoEm(p.getPagoEm())
                .nomeCliente(p.getCliente() != null ? p.getCliente().getNome() : null)
                .telefoneCliente(p.getCliente() != null ? p.getCliente().getTelefone() : null)
                .enderecoEntrega(p.getEnderecoEntrega()).observacao(p.getObservacao())
                .subtotal(subtotalOut).taxaEntrega(p.getTaxaEntrega()).total(totalOut)
                .criadoEm(p.getCriadoEm())
                .agendadoPara(p.getAgendadoPara())
                .restauranteNome(p.getRestaurante() != null ? p.getRestaurante().getNome() : null)
                .restauranteTelefone(p.getRestaurante() != null ? p.getRestaurante().getTelefone() : null)
                .restauranteTempoEntrega(p.getRestaurante() != null ? p.getRestaurante().getTempoEntrega() : null)
                .restauranteTempoEntregaMax(p.getRestaurante() != null ? p.getRestaurante().getTempoEntregaMax() : null)
                .entregadorId(p.getEntregador() != null ? p.getEntregador().getId() : null)
                .entregadorNome(p.getEntregador() != null ? p.getEntregador().getNome() : null)
                .mesaId(p.getMesa() != null ? p.getMesa().getId() : null)
                .mesaNome(p.getMesa() != null ? p.getMesa().getNome() : null)
                .mesaSlug(p.getMesa() != null ? p.getMesa().getSlug() : null)
                .nomeClienteMesa(p.getNomeClienteMesa())
                .nomeChamada(p.getNomeChamada())
                // Devolve um nomeCliente "util" pra UI mesmo quando nao ha Cliente
                // vinculado no banco. Ordem de fallback:
                //   1. Cliente cadastrado (delivery via cardapio publico)
                //   2. nomeClienteMesa (cliente digitou na mesa via QR)
                //   3. nomeChamada (balcao — dono digitou na hora da venda)
                // Antes ignorava (3) e card aparecia generico "Cliente".
                .nomeCliente(
                    p.getCliente() != null ? p.getCliente().getNome()
                    : p.getNomeClienteMesa() != null && !p.getNomeClienteMesa().isBlank()
                            ? p.getNomeClienteMesa()
                    : p.getNomeChamada()
                )
                .divisaoPagamentos(extrairDivisaoDaSessao(p.getSessaoId()))
                .incluiuServico(extrairServicoDaSessao(p.getSessaoId()))
                .valorCobradoSessao(extrairValorCobradoDaSessao(p.getSessaoId()))
                // Origem do pedido: MYDELIVERY (default) ou IFOOD. Frontend
                // usa pra mostrar a logo no card/drawer.
                .origem(p.getOrigem() == null ? "MYDELIVERY" : p.getOrigem().name())
                .ifoodDisplayId(p.getIfoodDisplayId())
                .itens(itens).build();
    }

    /** Cache leve do JSON parseado por sessaoId — evita re-parsear em
     *  cada chamada do getter quando o mesmo pedido tem múltiplos campos
     *  vindos da mesma sessão. */
    private final java.util.Map<Long, java.util.Map<String, Object>> _sessaoPayloadCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private java.util.Map<String, Object> carregarPayloadSessao(Long sessaoId) {
        if (sessaoId == null) return null;
        return _sessaoPayloadCache.computeIfAbsent(sessaoId, id -> {
            try {
                var sess = mesaSessaoRepository.findById(id).orElse(null);
                if (sess == null || sess.getPagamentosJson() == null) return java.util.Map.of();
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> parsed = mapper.readValue(
                        sess.getPagamentosJson(), java.util.Map.class);
                return parsed != null ? parsed : java.util.Map.of();
            } catch (Exception e) {
                return java.util.Map.of();
            }
        });
    }

    /** Limpa o cache de payload da sessão. Chamar ao final de operações de
     *  fechamento/edição. Como o service é singleton e o cache nunca cresce
     *  além do número de sessões ativas no dia, em prática não precisa GC. */
    public void limparCachePayloadSessao() { _sessaoPayloadCache.clear(); }

    private List<PedidoResponse.DivisaoPagamentoResponse> extrairDivisaoDaSessao(Long sessaoId) {
        var payload = carregarPayloadSessao(sessaoId);
        if (payload == null || payload.isEmpty()) return null;
        Object divisaoObj = payload.get("divisao");
        if (!(divisaoObj instanceof java.util.List<?> divisaoList) || divisaoList.isEmpty()) return null;
        List<PedidoResponse.DivisaoPagamentoResponse> out = new java.util.ArrayList<>();
        for (Object item : divisaoList) {
            if (!(item instanceof java.util.Map<?, ?> m)) continue;
            try {
                Object pessoa = m.get("pessoa");
                Object total = m.get("total");
                Object forma = m.get("formaPagamento");
                out.add(PedidoResponse.DivisaoPagamentoResponse.builder()
                        .pessoa(pessoa == null ? null : Integer.valueOf(pessoa.toString()))
                        .total(total == null ? null : new java.math.BigDecimal(total.toString()))
                        .formaPagamento(forma == null ? null : forma.toString())
                        .build());
            } catch (Exception ignore) { /* item malformado, pula */ }
        }
        return out.isEmpty() ? null : out;
    }

    private Boolean extrairServicoDaSessao(Long sessaoId) {
        var payload = carregarPayloadSessao(sessaoId);
        if (payload == null || payload.isEmpty()) return null;
        Object v = payload.get("comServico");
        if (v instanceof Boolean b) return b;
        if (v != null) return Boolean.parseBoolean(v.toString());
        return null;
    }

    private java.math.BigDecimal extrairValorCobradoDaSessao(Long sessaoId) {
        if (sessaoId == null) return null;
        try {
            var sess = mesaSessaoRepository.findById(sessaoId).orElse(null);
            return sess == null ? null : sess.getValorCobrado();
        } catch (Exception e) { return null; }
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
        // Mesma normalização do endpoint de consulta (PublicController.consultarTaxaPorBairro):
        // romanos↔árabicos, sem acento, abreviações comuns.
        return r.getBairrosAtendidos().stream()
                .filter(b -> b != null && b.getNome() != null)
                .filter(b -> com.mydelivery.util.BairroNormalizer.combina(b.getNome(), bairroCliente))
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

    /**
     * Fallback de cobrança de complementos: extrai a soma dos valores
     * em parênteses da observação do item.
     *
     * O frontend monta a obs no formato:
     *   "+ Leite em pó, Granola, Frozen (R$ 3,00)"
     *   "+ Bacon ×3 (R$ 6,00), Queijo extra (R$ 4,00)"
     *
     * Esta função casa todos os "(R$ X,XX)" / "(R$ X.XX)" e soma. Usada só
     * quando o frontend NAO envia o `preco` somado (browser cacheado/antigo)
     * — assim mesmo com cliente atrasado, o pedido sai com o total certo.
     *
     * Retorna ZERO se a obs for null/vazia ou se nao houver match.
     */
    private static final java.util.regex.Pattern COMPLEMENTO_PRECO_RX =
            java.util.regex.Pattern.compile("\\(\\s*R\\$\\s*([0-9]+(?:[.,][0-9]{1,2})?)\\s*\\)");

    private static BigDecimal extrairValorComplementosDaObs(String obs) {
        if (obs == null || obs.isBlank()) return BigDecimal.ZERO;
        java.util.regex.Matcher m = COMPLEMENTO_PRECO_RX.matcher(obs);
        BigDecimal soma = BigDecimal.ZERO;
        while (m.find()) {
            try {
                // Normaliza decimal pt-BR ("3,00") pra formato Java ("3.00")
                String num = m.group(1).replace(",", ".");
                soma = soma.add(new BigDecimal(num));
            } catch (NumberFormatException ignore) {
                // Ignora valor malformado — não trava o pedido por isso
            }
        }
        return soma;
    }
}
