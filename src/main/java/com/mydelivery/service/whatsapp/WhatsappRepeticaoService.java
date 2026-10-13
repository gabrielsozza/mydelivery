package com.mydelivery.service.whatsapp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.security.SecureRandom;
import java.util.Base64;
import java.security.MessageDigest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.pedido.NovoPedidoRequest;
import com.mydelivery.dto.pedido.PedidoResponse;
import com.mydelivery.model.ConfiguracaoRestaurante;
import com.mydelivery.model.ComplementoGrupo;
import com.mydelivery.model.ComplementoItem;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.PedidoItem;
import com.mydelivery.model.PedidoItemComplemento;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.model.WhatsappRepeticaoConversa;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.repository.WhatsappRepeticaoConversaRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Fluxo persistente para repetir pedidos com confirmação explícita e checkout oficial. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsappRepeticaoService {
    private static final int EXPIRACAO_MINUTOS = 30;
    private static final int LINK_EXPIRACAO_HORAS = 24;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern PRECO_OBSERVACAO = Pattern.compile("\\(\\s*\\+?\\s*R\\$\\s*[0-9]+(?:[.,][0-9]{1,2})?\\s*\\)");
    private static final Pattern QUANTIDADE_COMPLEMENTO = Pattern.compile("\\s*[×x]\\s*(\\d+)\\s*$");
    private static final Pattern FORMA_PAGAMENTO = Pattern.compile("\\b(pix|credito|debito|dinheiro|especie)\\b");
    private static final Pattern INTENCAO_PAGAMENTO = Pattern.compile(
            "\\b(?:quero|prefiro|vou)(?:\\s+pagar)?\\s+(?:(?:no|na|com|em|por|de)\\s+)?(pix|credito|debito|dinheiro|especie)\\b"
            + "|\\b(?:para|pra|pro)\\s+(pix|credito|debito|dinheiro|especie)\\b");
    private static final Set<Pedido.Status> STATUS_REPETIVEIS = Set.of(
            Pedido.Status.CONFIRMADO, Pedido.Status.EM_PREPARO, Pedido.Status.PRONTO,
            Pedido.Status.SAIU_ENTREGA, Pedido.Status.ENTREGUE);

    private final WhatsappRepeticaoConversaRepository conversaRepo;
    private final PedidoRepository pedidoRepo;
    private final ConfiguracaoRestauranteRepository configRepo;
    private final RestauranteRepository restauranteRepo;
    private final com.mydelivery.repository.ComplementoGrupoRepository grupoRepo;
    private final com.mydelivery.service.PedidoService pedidoService;

    /** Devolve null quando a repetição não está habilitada ou não há contexto. */
    @Transactional
    public String processar(Restaurante restaurante, String telefoneOrigem, String texto, String linkCardapio) {
        if (restaurante == null || !Boolean.TRUE.equals(restaurante.getRepeticaoInteligenteWhatsapp())) return null;
        String telefone = com.mydelivery.util.TelefoneUtil.normalizar(telefoneOrigem);
        if (telefone == null || telefone.isBlank()) return null;
        String telefoneHash = hashToken(telefone);
        LocalDateTime agora = LocalDateTime.now();
        var ativaOpt = conversaRepo.findTopByRestauranteIdAndTelefoneHashAndExpiraEmAfterOrderByIdDesc(
                restaurante.getId(), telefoneHash, agora);

        if (ativaOpt.isEmpty()) {
            conversaRepo.findTopByRestauranteIdAndTelefoneHashOrderByIdDesc(restaurante.getId(), telefoneHash)
                    .filter(c -> c.getExpiraEm() != null && !c.getExpiraEm().isAfter(agora)
                            && !Set.of(WhatsappRepeticaoConversa.Estado.PEDIDO_CRIADO,
                                    WhatsappRepeticaoConversa.Estado.RECUSADO,
                                    WhatsappRepeticaoConversa.Estado.EXPIRADO,
                                    WhatsappRepeticaoConversa.Estado.ENCAMINHADO_CARDAPIO).contains(c.getEstado()))
                    .ifPresent(c -> c.setEstado(WhatsappRepeticaoConversa.Estado.EXPIRADO));
            return ofertarSeElegivel(restaurante, telefone, texto, linkCardapio, agora);
        }

        WhatsappRepeticaoConversa conversa = conversaRepo.findByIdForUpdate(ativaOpt.get().getId()).orElse(null);
        if (conversa == null || conversa.getEstado() == null) return null;
        if (conversa.getEstado() == WhatsappRepeticaoConversa.Estado.PEDIDO_CRIADO) {
            Long id = conversa.getPedidoCriado() == null ? null : conversa.getPedidoCriado().getId();
            String mensagem = normalizar(texto);
            if (mensagem.contains("comprovante") || mensagem.contains("ja paguei") || mensagem.contains("paguei no pix"))
                return "Obrigado" + vocativo(conversa.getPedidoReferencia())
                        + ". A equipe da loja vai conferir o comprovante do pedido *#" + id + "* aqui no WhatsApp.";
            if (mensagem.contains("repetir") || mensagem.contains("mesmo pedido") || mensagem.contains("confirmar pedido"))
                return "Esse pedido já foi registrado como *#" + id + "*. Acompanhe em:\n"
                        + "https://mydeliveryfood.com.br/acompanhar.html?id=" + id;
            return null;
        }
        String t = normalizar(texto);
        if (conversa.getEstado() == WhatsappRepeticaoConversa.Estado.RECUSADO
                || conversa.getEstado() == WhatsappRepeticaoConversa.Estado.EXPIRADO) return null;
        if (conversa.getEstado() == WhatsappRepeticaoConversa.Estado.ENCAMINHADO_CARDAPIO) {
            // Conversas antigas podem ter sido desviadas ao cardápio pelo bloqueio
            // de personalizações. Nova tentativa explícita deve ser reavaliada.
            if (detectarPagamento(t) == null && !t.contains("repetir") && !t.contains("repete")
                    && !t.contains("mesmo pedido")) return null;
            conversa.setEstado(WhatsappRepeticaoConversa.Estado.OFERTA_DE_REPETICAO);
        }
        if (pediuHumano(t)) {
            conversa.setEstado(WhatsappRepeticaoConversa.Estado.RECUSADO);
            return null; // O fluxo existente transfere para atendimento humano.
        }

        if (conversa.getEstado() == WhatsappRepeticaoConversa.Estado.OFERTA_DE_REPETICAO) {
            if (negativa(t) && detectarPagamento(t) == null && !t.contains("pagar")) {
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.RECUSADO);
                return "Tudo bem. Você pode escolher outro pedido pelo cardápio:\n" + linkCardapio;
            }
            if (!positivaRepeticao(t) && detectarPagamento(t) == null && !formaCartaoAmbigua(t)
                    && !(negativa(t) && t.contains("pagar"))) return null;
            Pedido referencia = conversa.getPedidoReferencia();
            conversa.setAceitoEm(agora);
            if (solicitouPagamentoOnline(t)) {
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.ENCAMINHADO_CARDAPIO);
                return "Para pagar online, use o checkout seguro do cardápio:\n" + linkCardapio;
            }
            if (solicitouAlteracaoItens(t)) {
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.ENCAMINHADO_CARDAPIO);
                return "Para ajustar itens com segurança, abra o pedido no cardápio. Não apliquei essa alteração automaticamente:\n" + linkCardapio;
            }
            String pagamento = detectarPagamento(t);
            if (formaCartaoAmbigua(t)) {
                conversa.setPagamentoEscolhido(null);
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.VALIDANDO_ENDERECO);
                return "Você prefere crédito ou débito na entrega?";
            }
            if (negativa(t) && pagamento == null) {
                conversa.setPagamentoEscolhido(null);
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.VALIDANDO_ENDERECO);
                return "Sem problema. Qual forma você prefere: Pix pela chave da loja, dinheiro, crédito ou débito na entrega?";
            }
            if (pagamento == null) pagamento = pagamentoAnteriorDisponivel(referencia, restaurante.getId());
            if (pagamento == null) {
                conversa.setPagamentoEscolhido(null);
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.VALIDANDO_ENDERECO);
                return "Qual forma de pagamento você prefere: Pix na entrega, dinheiro, crédito ou débito na entrega?";
            }
            String erroPagamento = validarPagamento(pagamento, restaurante.getId());
            if (erroPagamento != null) return erroPagamento;
            String problema = validarEstruturaRepetivel(referencia, restaurante.getId(), pagamento);
            if (problema != null) {
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.ENCAMINHADO_CARDAPIO);
                return problema + "\n\nVocê pode montar e editar seu pedido pelo cardápio:\n" + linkCardapio;
            }
            conversa.setPagamentoEscolhido(pagamento);
            conversa.setAceitoEm(agora);
            if (detectarPagamento(t) != null) {
                String falha = prepararResumo(conversa, restaurante, linkCardapio);
                if (falha != null) return falha + "\n\nConfira pelo cardápio:\n" + linkCardapio;
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.AGUARDANDO_CONFIRMACAO_FINAL);
                return confirmarTrocaPagamento(conversa, restaurante);
            }
            conversa.setEstado(WhatsappRepeticaoConversa.Estado.VALIDANDO_ENDERECO);
            return perguntaEndereco(referencia);
        }

        if (conversa.getEstado() == WhatsappRepeticaoConversa.Estado.VALIDANDO_ENDERECO) {
            if (negouFormaPagamento(t) && detectarPagamento(t) == null)
                return "Sem problema" + vocativo(conversa.getPedidoReferencia())
                        + ". Qual forma você prefere: Pix, dinheiro, crédito ou débito?";
            if (corrigirEscolha(t) && detectarPagamento(t) == null) return "Sem problema" + vocativo(conversa.getPedidoReferencia())
                    + ". Quer mudar o pagamento para Pix, crédito, débito ou dinheiro?";
            if (solicitouPagamentoOnline(t)) {
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.ENCAMINHADO_CARDAPIO);
                return "Para pagar online, use o checkout seguro do cardápio:\n" + linkCardapio;
            }
            if (formaCartaoAmbigua(t)) return "Você prefere crédito ou débito na entrega?";
            String pagamentoAtualizado = detectarPagamento(t);
            if (pagamentoAtualizado != null && conversa.getPagamentoEscolhido() != null) {
                String erro = validarPagamento(pagamentoAtualizado, restaurante.getId());
                if (erro != null) return erro;
                conversa.setPagamentoEscolhido(pagamentoAtualizado);
                String falha = prepararResumo(conversa, restaurante, linkCardapio);
                if (falha != null) return falha + "\n\nConfira pelo cardápio:\n" + linkCardapio;
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.AGUARDANDO_CONFIRMACAO_FINAL);
                return confirmarTrocaPagamento(conversa, restaurante);
            }
            if (conversa.getPagamentoEscolhido() == null) {
                String forma = detectarPagamento(t);
                if (forma == null) return "Não consegui identificar a forma de pagamento. Escolha Pix na entrega, dinheiro, crédito ou débito na entrega.";
                String erro = validarPagamento(forma, restaurante.getId());
                if (erro != null) return erro;
                conversa.setPagamentoEscolhido(forma);
                String falha = prepararResumo(conversa, restaurante, linkCardapio);
                if (falha != null) return falha + "\n\nConfira pelo cardápio:\n" + linkCardapio;
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.AGUARDANDO_CONFIRMACAO_FINAL);
                return confirmarTrocaPagamento(conversa, restaurante);
            }
            if (querMudarEndereco(t)) {
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.ENCAMINHADO_CARDAPIO);
                return "Para informar outro endereço com segurança, continue pelo cardápio:\n" + linkCardapio;
            }
            if (!positiva(t)) return "Vamos entregar novamente em:\n" + enderecoCompleto(conversa.getPedidoReferencia())
                    + "\nResponda *sim* ou escolha outro endereço pelo cardápio:\n" + linkCardapio;
            conversa.setEnderecoConfirmado(true);
            String erro = prepararResumo(conversa, restaurante, linkCardapio);
            if (erro != null) {
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.ENCAMINHADO_CARDAPIO);
                return erro + "\n\nVocê pode continuar pelo cardápio:\n" + linkCardapio;
            }
            conversa.setEstado(WhatsappRepeticaoConversa.Estado.AGUARDANDO_CONFIRMACAO_FINAL);
            return montarResumo(conversa, restaurante) + "\n\nPosso confirmar o pedido" + vocativo(conversa.getPedidoReferencia()) + "?";
        }

        if (conversa.getEstado() == WhatsappRepeticaoConversa.Estado.AGUARDANDO_CONFIRMACAO_FINAL) {
            if (negouFormaPagamento(t) && detectarPagamento(t) == null)
                return "Sem problema" + vocativo(conversa.getPedidoReferencia())
                        + ". Qual forma você prefere: Pix, dinheiro, crédito ou débito?";
            if (corrigirEscolha(t) && detectarPagamento(t) == null) return "Sem problema" + vocativo(conversa.getPedidoReferencia())
                    + ". Quer mudar o pagamento para Pix, crédito, débito ou dinheiro?";
            if (solicitouPagamentoOnline(t)) {
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.ENCAMINHADO_CARDAPIO);
                return "Para pagar online, use o checkout seguro do cardápio:\n" + linkCardapio;
            }
            if (solicitouAlteracaoItens(t) || querMudarEndereco(t)) {
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.ENCAMINHADO_CARDAPIO);
                return "Para alterar o pedido ou o endereço, continue pelo cardápio:\n" + linkCardapio;
            }
            if (formaCartaoAmbigua(t)) return "Você prefere crédito ou débito na entrega?";
            String pagamentoAtualizado = detectarPagamento(t);
            if (pagamentoAtualizado != null && !pagamentoAtualizado.equals(conversa.getPagamentoEscolhido())) {
                String erro = validarPagamento(pagamentoAtualizado, restaurante.getId());
                if (erro != null) return erro;
                conversa.setPagamentoEscolhido(pagamentoAtualizado);
                String falha = prepararResumo(conversa, restaurante, linkCardapio);
                if (falha != null) return falha + "\n" + linkCardapio;
                return confirmarTrocaPagamento(conversa, restaurante);
            }
            if (pagamentoAtualizado != null) return confirmarTrocaPagamento(conversa, restaurante);
            if (negativa(t)) {
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.RECUSADO);
                return "Tudo bem. Se quiser ajustar os itens, use o cardápio:\n" + linkCardapio;
            }
            if (!confirmacaoFinal(t)) return null;
            try {
                String erroPagamento = validarPagamento(conversa.getPagamentoEscolhido(), restaurante.getId());
                if (erroPagamento != null) return erroPagamento;
                String chavePix = "pix".equals(conversa.getPagamentoEscolhido()) ? chavePixLoja(restaurante.getId()) : null;
                if ("pix".equals(conversa.getPagamentoEscolhido()) && chavePix == null)
                    return "A loja ainda não cadastrou uma chave Pix. Escolha outra forma de pagamento antes de confirmar.";
                String erroValidacao = validarEstruturaRepetivel(conversa.getPedidoReferencia(), restaurante.getId(), conversa.getPagamentoEscolhido());
                if (erroValidacao != null) throw new IllegalStateException(erroValidacao);
                NovoPedidoRequest request = montarRequest(conversa, restaurante);
                PedidoResponse criado = pedidoService.criarPedidoViaWhatsappRepeticao(request, conversa.getId());
                Pedido pedido = pedidoRepo.findById(criado.getId()).orElseThrow();
                conversa.setPedidoCriado(pedido);
                conversa.setEstado(WhatsappRepeticaoConversa.Estado.PEDIDO_CRIADO);
                conversa.setExpiraEm(agora.plusMinutes(EXPIRACAO_MINUTOS));
                conversa.setLinkTokenHash(null);
                conversa.setLinkTokenExpiraEm(null);
                String linkAcompanhar = criado.getRastreamentoTokenCliente() != null
                        ? "https://mydeliveryfood.com.br/acompanhar.html?token=" + criado.getRastreamentoTokenCliente()
                        : "https://mydeliveryfood.com.br/acompanhar.html?id=" + criado.getId();
                String resposta = "Pedido *#" + criado.getId() + "* confirmado" + vocativo(conversa.getPedidoReferencia())
                        + " e enviado para a loja.\n";
                if ("pix".equals(conversa.getPagamentoEscolhido())) {
                    resposta += "\nChave Pix da loja: *" + chavePix + "*\nValor: *R$ " + moeda(pedido.getTotal())
                            + "*\nDepois de pagar, envie o comprovante aqui no WhatsApp para a loja conferir.\n";
                }
                return resposta + "\nAcompanhe por aqui:\n" + linkAcompanhar;
            } catch (RuntimeException e) {
                log.warn("[Bot:Repeticao] criação falhou para restaurante={} telefone={}***: {}",
                        restaurante.getId(), telefone.substring(0, Math.min(5, telefone.length())), e.getMessage());
                if (e.getMessage() != null && e.getMessage().contains("apenas por retirada")) {
                    return "Hoje estamos atendendo apenas por retirada no local. Entregas estão pausadas."
                            + "\n\nFaça seu pedido para retirada aqui: " + linkCardapio;
                }
                return "Não consegui confirmar esse pedido porque os dados da loja mudaram. Confira e finalize pelo cardápio:\n" + linkCardapio;
            }
        }
        return null;
    }

    @Transactional
    public void encerrarPorAtendimentoHumano(Long restauranteId, String telefoneOrigem) {
        String telefone = com.mydelivery.util.TelefoneUtil.normalizar(telefoneOrigem);
        if (restauranteId == null || telefone == null) return;
        conversaRepo.findTopByRestauranteIdAndTelefoneHashOrderByIdDesc(restauranteId, hashToken(telefone))
                .filter(c -> !Set.of(WhatsappRepeticaoConversa.Estado.PEDIDO_CRIADO,
                        WhatsappRepeticaoConversa.Estado.RECUSADO,
                        WhatsappRepeticaoConversa.Estado.EXPIRADO,
                        WhatsappRepeticaoConversa.Estado.ENCAMINHADO_CARDAPIO).contains(c.getEstado()))
                .ifPresent(c -> c.setEstado(WhatsappRepeticaoConversa.Estado.ENCAMINHADO_CARDAPIO));
    }

    private String ofertarSeElegivel(Restaurante r, String telefone, String texto, String link, LocalDateTime agora) {
        if (pediuHumano(normalizar(texto))) return null;
        Collection<String> telefones = variantesTelefone(telefone);
        if (telefones.isEmpty()) return null;
        List<Pedido> pedidos = pedidoRepo.findPedidosRepetiveis(r.getId(), telefones,
                STATUS_REPETIVEIS, List.of(Pedido.Tipo.DELIVERY, Pedido.Tipo.RETIRADA));
        Pedido referencia = pedidos.stream().filter(p -> p.getCliente() != null
                && p.getCliente().getTelefone() != null
                && telefones.contains(com.mydelivery.util.TelefoneUtil.normalizar(p.getCliente().getTelefone())))
                .findFirst().orElse(null);
        if (referencia == null) return null;
        String telefoneHash = hashToken(telefone);
        var existente = conversaRepo.findByRestauranteIdAndTelefoneHashAndPedidoReferenciaId(r.getId(), telefoneHash, referencia.getId());
        if (existente.isPresent()) return null;
        WhatsappRepeticaoConversa c = new WhatsappRepeticaoConversa();
        c.setRestaurante(r);
        c.setTelefoneHash(telefoneHash);
        c.setPedidoReferencia(referencia);
        c.setEstado(WhatsappRepeticaoConversa.Estado.OFERTA_DE_REPETICAO);
        c.setExpiraEm(agora.plusMinutes(EXPIRACAO_MINUTOS));
        String token = gerarToken();
        c.setLinkTokenHash(hashToken(token));
        c.setLinkTokenExpiraEm(agora.plusHours(LINK_EXPIRACAO_HORAS));
        // A restrição única (loja, telefone, pedido de referência) impede que
        // retries concorrentes registrem duas ofertas para a mesma compra.
        conversaRepo.saveAndFlush(c);
        String linkComCarrinho = link + (link.contains("?") ? "&" : "?") + "repeticao="
                + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
        return montarOferta(referencia, linkComCarrinho);
    }

    private String validarEstruturaRepetivel(Pedido p, Long restauranteId, String pagamento) {
        if (p == null || p.getRestaurante() == null || !restauranteId.equals(p.getRestaurante().getId())
                || p.getItens() == null || p.getItens().isEmpty()) return "Não consegui validar os itens do pedido anterior.";
        for (PedidoItem item : p.getItens()) {
            Produto produto = item.getProduto();
            if (produto == null || produto.getRestaurante() == null
                    || !restauranteId.equals(produto.getRestaurante().getId())
                    || !Boolean.TRUE.equals(produto.getDisponivel())) {
                return "Um ou mais itens do pedido anterior não estão mais disponíveis.";
            }
            if (item.getQuantidade() == null || item.getQuantidade() < 1 || item.getQuantidade() > 50) {
                return "Não consegui validar a quantidade de um dos itens.";
            }
            if (produto.getTipo() == Produto.Tipo.COMBO || Boolean.TRUE.equals(produto.getPrecoAPartirDe())) {
                return "Um item do pedido precisa ser conferido pelo cardápio antes de repetir.";
            }
            if (!produto.disponivelNoHorario(java.time.ZonedDateTime.now(java.time.ZoneId.of("America/Sao_Paulo"))))
                return "Um item do pedido não está disponível neste horário.";
            try {
                reconstruirItem(item, pagamento);
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }
        if (p.getTipo() == Pedido.Tipo.DELIVERY && !temEndereco(p.getCliente())) {
            return "Não encontrei um endereço completo salvo para repetir a entrega.";
        }
        if (p.getTipo() == Pedido.Tipo.DELIVERY && p.getEnderecoEntrega() != null
                && !enderecoBateComPedido(p)) {
            return "O endereço salvo mudou desde esse pedido. Confira o endereço no cardápio antes de continuar.";
        }
        return null;
    }

    private String prepararResumo(WhatsappRepeticaoConversa c, Restaurante r, String link) {
        Pedido p = c.getPedidoReferencia();
        String estrutura = validarEstruturaRepetivel(p, r.getId(), c.getPagamentoEscolhido());
        if (estrutura != null) return estrutura;
        if (p.getTipo() == Pedido.Tipo.DELIVERY) {
            var end = endereco(p);
            try {
                com.mydelivery.util.EnderecoPedidoValidator.validar(end);
                if (!Boolean.TRUE.equals(r.getAberto())) return "A loja está fechada e não está aceitando pedidos agora.";
                BigDecimal subtotal = subtotalAtual(p, c.getPagamentoEscolhido());
                if (r.getPedidoMinimo() != null && subtotal.compareTo(r.getPedidoMinimo()) < 0)
                    return "O pedido atualizado fica abaixo do mínimo atual da loja.";
                BigDecimal taxa = pedidoService.calcularTaxaEntrega(r, end);
                if (r.getFreteGratisApartirDe() != null && r.getFreteGratisApartirDe().signum() > 0
                        && subtotal.compareTo(r.getFreteGratisApartirDe()) >= 0) taxa = BigDecimal.ZERO;
                return null;
            } catch (RuntimeException e) {
                return "Não consegui validar a taxa e a área de entrega do endereço atual.";
            }
        }
        return null;
    }

    private String montarResumo(WhatsappRepeticaoConversa c, Restaurante r) {
        Pedido p = c.getPedidoReferencia();
        String pagamento = c.getPagamentoEscolhido();
        BigDecimal subtotal = subtotalAtual(p, pagamento);
        if (r.getPedidoMinimo() != null && subtotal.compareTo(r.getPedidoMinimo()) < 0) return "O pedido atualizado fica abaixo do mínimo atual da loja.";
        BigDecimal taxa = BigDecimal.ZERO;
        String enderecoTexto = "Retirada no local";
        if (p.getTipo() == Pedido.Tipo.DELIVERY) {
            Map<String, String> end = endereco(p);
            taxa = pedidoService.calcularTaxaEntrega(r, end);
            if (r.getFreteGratisApartirDe() != null && subtotal.compareTo(r.getFreteGratisApartirDe()) >= 0)
                taxa = BigDecimal.ZERO;
            enderecoTexto = enderecoCompleto(p);
        }
        BigDecimal total = subtotal.add(taxa).setScale(2, RoundingMode.HALF_UP);
        StringBuilder s = new StringBuilder("Seu pedido ficou assim" + vocativo(p) + ":\n\n");
        for (PedidoItem item : p.getItens()) {
            Produto produto = item.getProduto();
            ItemAtual atualizado = reconstruirItem(item, pagamento);
            BigDecimal preco = atualizado.preco();
            s.append(item.getQuantidade()).append("x ").append(produto.getNome()).append(" — R$ ")
                    .append(moeda(preco.multiply(BigDecimal.valueOf(item.getQuantidade())))).append("\n");
            if (!atualizado.observacao().isBlank())
                s.append("  Escolhas/observações: ").append(atualizado.observacao().replace("\n", "; ")).append("\n");
        }
        if (p.getTipo() == Pedido.Tipo.DELIVERY) s.append("\nEntrega: R$ ").append(moeda(taxa)).append("\n");
        s.append("Total: R$ ").append(moeda(total)).append("\n\nEndereço: ").append(enderecoTexto)
                .append("\nPagamento: ").append(rotuloPagamento(pagamento));
        return s.toString();
    }

    private NovoPedidoRequest montarRequest(WhatsappRepeticaoConversa c, Restaurante r) {
        Pedido anterior = c.getPedidoReferencia();
        NovoPedidoRequest req = new NovoPedidoRequest();
        req.setSlug(r.getSlug());
        req.setModo(anterior.getTipo() == Pedido.Tipo.DELIVERY ? "delivery" : "retirada");
        req.setPagamento(c.getPagamentoEscolhido());
        req.setModoPagamento("entrega");
        NovoPedidoRequest.ClienteDto cliente = new NovoPedidoRequest.ClienteDto();
        cliente.setNome(anterior.getCliente().getNome());
        cliente.setTelefone(anterior.getCliente().getTelefone());
        req.setCliente(cliente);
        if (anterior.getTipo() == Pedido.Tipo.DELIVERY) req.setEndereco(endereco(anterior));
        List<NovoPedidoRequest.ItemDto> itens = new ArrayList<>();
        for (PedidoItem item : anterior.getItens()) {
            Produto produto = item.getProduto();
            NovoPedidoRequest.ItemDto i = new NovoPedidoRequest.ItemDto();
            i.setProdutoId(produto.getId());
            i.setQty(item.getQuantidade());
            ItemAtual atualizado = reconstruirItem(item, c.getPagamentoEscolhido());
            i.setPreco(atualizado.preco());
            i.setPrecoBasePagamento(preco(produto, c.getPagamentoEscolhido()));
            i.setObs(atualizado.observacao());
            i.setComplementos(atualizado.complementos());
            itens.add(i);
        }
        req.setItens(itens);
        return req;
    }

    private String montarOferta(Pedido p, String link) {
        StringBuilder s = new StringBuilder("Olá" + vocativo(p) + "! Encontrei seu último pedido:\n\n");
        int limite = Math.min(p.getItens().size(), 8);
        for (int n = 0; n < limite; n++) {
            PedidoItem item = p.getItens().get(n);
            String nome = item.getNomeProduto();
            if ((nome == null || nome.isBlank()) && item.getProduto() != null) nome = item.getProduto().getNome();
            s.append(item.getQuantidade()).append("x ").append(nome == null ? "Item do cardápio" : nome).append("\n");
        }
        if (p.getItens().size() > limite) s.append("e mais ").append(p.getItens().size() - limite).append(" item(ns)\n");
        if (p.getTipo() == Pedido.Tipo.DELIVERY && temEndereco(p.getCliente()))
            s.append("\nEndereço de entrega:\n").append(enderecoCompleto(p));
        if (p.getFormaPagamento() != null)
            s.append("\nPagamento anterior: ").append(rotuloPagamentoAnterior(p));
        if (p.getTotal() != null) s.append("\nTotal anterior: R$ ").append(moeda(p.getTotal()));
        s.append("\n\nGostaria de repetir? Se mudou algum item, me diga.\n\nSe preferir, escolha pelo cardápio:\n").append(link);
        return s.toString();
    }

    private String perguntaEndereco(Pedido p) {
        if (p.getTipo() != Pedido.Tipo.DELIVERY) return "Vou preparar o resumo atualizado para retirada.";
        var c = p.getCliente();
        if (!temEndereco(c)) return "Não encontrei o endereço salvo. Continue pelo cardápio para informar um endereço.";
        return "Vamos entregar novamente neste endereço" + vocativo(p) + "?\n"
                + enderecoCompleto(p) + "\nResponda *sim* para conferir os valores atuais.";
    }

    private Map<String, String> endereco(Pedido p) {
        var c = p.getCliente();
        return com.mydelivery.util.EnderecoPedidoValidator.validar(Map.of(
                "rua", vazio(c.getEnderecoRua()), "numero", vazio(c.getEnderecoNumero()),
                "bairro", vazio(c.getEnderecoBairro()), "complemento", vazio(c.getEnderecoComplemento()),
                "referencia", vazio(c.getEnderecoReferencia()), "cidade", vazio(c.getEnderecoCidade()),
                "estado", vazio(c.getEnderecoEstado()), "cep", vazio(c.getEnderecoCep())));
    }

    private record EscolhaAtual(ComplementoGrupo grupo, ComplementoItem item, int quantidade) {}
    private record ItemAtual(BigDecimal preco, String observacao, List<NovoPedidoRequest.ComplementoDto> complementos) {}

    /** Reconstrói as escolhas salvas e recalcula pelo catálogo atual antes da confirmação. */
    private ItemAtual reconstruirItem(PedidoItem anterior, String pagamento) {
        Produto produto = anterior.getProduto();
        List<ComplementoGrupo> grupos = grupoRepo.findByProdutoIdOrderByIdAsc(produto.getId());
        if (grupos == null) grupos = List.of();
        String observacao = vazio(anterior.getObservacao());
        if (observacao.contains("Combo:") || produto.getTipo() == Produto.Tipo.COMBO)
            throw new IllegalArgumentException("O combo do pedido anterior precisa ser conferido pelo cardápio.");

        List<EscolhaAtual> escolhas = new ArrayList<>();
        List<String> notas = new ArrayList<>();
        boolean temIds = anterior.getComplementos() != null && !anterior.getComplementos().isEmpty();
        if (temIds) {
            for (PedidoItemComplemento salvo : anterior.getComplementos()) {
                Long itemId = salvo.getComplemento() == null ? null : salvo.getComplemento().getId();
                EscolhaAtual encontrada = null;
                for (ComplementoGrupo grupo : grupos) {
                    if (grupo.getItens() == null) continue;
                    for (ComplementoItem opcao : grupo.getItens()) {
                        if (Objects.equals(opcao.getId(), itemId)) encontrada = new EscolhaAtual(grupo, opcao, 1);
                    }
                }
                if (encontrada == null) throw new IllegalArgumentException("Um adicional do pedido anterior mudou. Confira pelo cardápio.");
                escolhas.add(encontrada);
            }
        }
        for (String linha : observacao.split("\\R")) {
            String limpa = linha.trim();
            if (limpa.startsWith("+ ")) {
                if (temIds) continue;
                String semPrecos = PRECO_OBSERVACAO.matcher(limpa.substring(2)).replaceAll("");
                for (String parte : semPrecos.split(",")) {
                    if (parte.isBlank()) continue;
                    Matcher quantidade = QUANTIDADE_COMPLEMENTO.matcher(parte);
                    int qtd = 1;
                    if (quantidade.find()) {
                        try { qtd = Integer.parseInt(quantidade.group(1)); }
                        catch (NumberFormatException ex) { throw new IllegalArgumentException("A quantidade de um adicional mudou. Confira pelo cardápio."); }
                        parte = parte.substring(0, quantidade.start());
                    }
                    String nome = normalizar(parte.replace(',', ' '));
                    EscolhaAtual encontrada = null;
                    for (ComplementoGrupo grupo : grupos) {
                        if (grupo.getItens() == null) continue;
                        for (ComplementoItem opcao : grupo.getItens()) {
                            if (!normalizar(opcao.getNome().replace(',', ' ')).equals(nome)) continue;
                            if (encontrada != null)
                                throw new IllegalArgumentException("Um adicional do pedido anterior está ambíguo. Confira pelo cardápio.");
                            encontrada = new EscolhaAtual(grupo, opcao, qtd);
                        }
                    }
                    if (encontrada == null)
                        throw new IllegalArgumentException("Um adicional do pedido anterior não está mais no cardápio.");
                    escolhas.add(encontrada);
                }
            } else if (!limpa.isBlank()) {
                notas.add(linha);
            }
        }

        Map<Long, Integer> quantidades = new HashMap<>();
        Map<Long, BigDecimal> soma = new HashMap<>();
        Map<Long, BigDecimal> maior = new HashMap<>();
        Set<Long> itensUnicos = new HashSet<>();
        List<NovoPedidoRequest.ComplementoDto> dto = new ArrayList<>();
        for (EscolhaAtual escolha : escolhas) {
            ComplementoGrupo grupo = escolha.grupo();
            ComplementoItem opcao = escolha.item();
            int qtd = escolha.quantidade();
            if (qtd < 1 || qtd > 1000 || Boolean.FALSE.equals(opcao.getAtivo())
                    || !itensUnicos.add(opcao.getId())
                    || (opcao.getMaxSelecoes() != null && opcao.getMaxSelecoes() > 0 && qtd > opcao.getMaxSelecoes())
                    || (grupo.getModoPreco() == ComplementoGrupo.ModoPreco.MEDIA && qtd != 1))
                throw new IllegalArgumentException("Um adicional do pedido anterior não está mais disponível. Confira pelo cardápio.");
            quantidades.merge(grupo.getId(), qtd, Integer::sum);
            BigDecimal valor = Objects.requireNonNullElse(opcao.getPrecoAdicional(), BigDecimal.ZERO)
                    .multiply(BigDecimal.valueOf(qtd));
            soma.merge(grupo.getId(), valor, BigDecimal::add);
            maior.merge(grupo.getId(), valor, BigDecimal::max);
            NovoPedidoRequest.ComplementoDto escolhaDto = new NovoPedidoRequest.ComplementoDto();
            escolhaDto.setGrupoId(grupo.getId());
            escolhaDto.setItemId(opcao.getId());
            escolhaDto.setQuantidade(qtd);
            dto.add(escolhaDto);
        }
        BigDecimal extras = BigDecimal.ZERO;
        boolean temMedia = false;
        for (ComplementoGrupo grupo : grupos) {
            int qtd = quantidades.getOrDefault(grupo.getId(), 0);
            int minimo = Boolean.TRUE.equals(grupo.getObrigatorio())
                    ? Math.max(1, Objects.requireNonNullElse(grupo.getMinEscolhas(), 0))
                    : Objects.requireNonNullElse(grupo.getMinEscolhas(), 0);
            if (qtd == 0 && Boolean.TRUE.equals(grupo.getPermitirNenhuma())) minimo = 0;
            if (qtd < minimo || qtd > Objects.requireNonNullElse(grupo.getMaxEscolhas(), 1))
                throw new IllegalArgumentException("As escolhas de um produto mudaram. Confira pelo cardápio.");
            BigDecimal valor = soma.get(grupo.getId());
            if (valor == null) continue;
            ComplementoGrupo.ModoPreco modo = Objects.requireNonNullElse(grupo.getModoPreco(), ComplementoGrupo.ModoPreco.SOMA);
            if (modo == ComplementoGrupo.ModoPreco.MAIOR) valor = maior.get(grupo.getId());
            if (modo == ComplementoGrupo.ModoPreco.MEDIA) {
                temMedia = true;
                valor = valor.divide(BigDecimal.valueOf(qtd > 1 ? 2 : 1), 2, RoundingMode.HALF_UP);
            }
            extras = extras.add(valor);
        }
        if (Boolean.TRUE.equals(produto.getPrecoVitrine()) && (escolhas.isEmpty() || anterior.getQuantidade() != 1))
            throw new IllegalArgumentException("Um produto de preço variável precisa ser conferido pelo cardápio.");
        BigDecimal base = preco(produto, pagamento);
        BigDecimal total = (temMedia || Boolean.TRUE.equals(produto.getPrecoVitrine()) ? BigDecimal.ZERO : base)
                .add(extras).setScale(2, RoundingMode.HALF_UP);
        if (total.signum() <= 0) throw new IllegalArgumentException("Não consegui recalcular o valor do pedido anterior.");
        StringBuilder obsAtual = new StringBuilder(String.join("\n", notas));
        if (!escolhas.isEmpty()) {
            if (!obsAtual.isEmpty()) obsAtual.append('\n');
            obsAtual.append("+ ");
            for (int i = 0; i < escolhas.size(); i++) {
                if (i > 0) obsAtual.append(", ");
                EscolhaAtual escolha = escolhas.get(i);
                obsAtual.append(escolha.item().getNome().replace(',', ' '));
                if (escolha.quantidade() > 1) obsAtual.append(" ×").append(escolha.quantidade());
            }
        }
        return new ItemAtual(total, obsAtual.toString(), dto);
    }

    private BigDecimal subtotalAtual(Pedido p, String pagamento) {
        return p.getItens().stream().map(i -> reconstruirItem(i, pagamento).preco()
                .multiply(BigDecimal.valueOf(i.getQuantidade())))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal preco(Produto p, String pagamento) {
        BigDecimal valor = switch (pagamento) {
            case "pix" -> p.getPrecoPix(); case "dinheiro" -> p.getPrecoDinheiro();
            case "credito" -> p.getPrecoCredito(); case "debito" -> p.getPrecoDebito();
            default -> null;
        };
        return (valor == null ? p.getPreco() : valor).setScale(2, RoundingMode.HALF_UP);
    }

    private String pagamentoAnteriorDisponivel(Pedido p, Long restauranteId) {
        // Pix online anterior vira Pix manual no WhatsApp: o pedido entra na loja
        // normalmente e o cliente recebe a chave cadastrada para enviar comprovante.
        if (p.getModoPagamento() != Pedido.ModoPagamento.NA_ENTREGA
                && p.getFormaPagamento() != Pedido.FormaPagamento.PIX) return null;
        String forma = switch (p.getFormaPagamento()) {
            case PIX -> "pix"; case DINHEIRO -> "dinheiro"; case CARTAO_CREDITO -> "credito";
            case CARTAO_DEBITO -> "debito"; case CARTAO_MAQUININHA -> null; default -> null;
        };
        return forma != null && validarPagamento(forma, restauranteId) == null ? forma : null;
    }

    private String validarPagamento(String forma, Long restauranteId) {
        ConfiguracaoRestaurante cfg = configRepo.findByRestauranteId(restauranteId).orElse(null);
        if (cfg == null) return "Não consegui confirmar as formas de pagamento atuais. Continue pelo cardápio.";
        boolean ok = switch (forma) {
            case "pix" -> Boolean.TRUE.equals(cfg.getAceitaPix());
            case "dinheiro" -> Boolean.TRUE.equals(cfg.getAceitaDinheiro());
            case "credito", "debito" -> Boolean.TRUE.equals(cfg.getAceitaCartaoMaquininha());
            default -> false;
        };
        if (!ok) return "Essa forma de pagamento não está habilitada agora. Escolha outra ou continue pelo cardápio.";
        if ("pix".equals(forma) && chavePixLoja(restauranteId) == null)
            return "A loja ainda não cadastrou uma chave Pix para pedidos pelo WhatsApp. Escolha outra forma de pagamento ou continue pelo cardápio.";
        return null;
    }

    private String chavePixLoja(Long restauranteId) {
        Restaurante loja = restauranteRepo.findById(restauranteId).orElse(null);
        if (loja != null && loja.getChavePixAntecipado() != null && !loja.getChavePixAntecipado().isBlank())
            return loja.getChavePixAntecipado().trim().replaceAll("[\\r\\n]", "");
        ConfiguracaoRestaurante cfg = configRepo.findByRestauranteId(restauranteId).orElse(null);
        return cfg == null || cfg.getPixChave() == null || cfg.getPixChave().isBlank()
                ? null : cfg.getPixChave().trim().replaceAll("[\\r\\n]", "");
    }

    private String confirmarTrocaPagamento(WhatsappRepeticaoConversa conversa, Restaurante restaurante) {
        Pedido referencia = conversa.getPedidoReferencia();
        String forma = switch (conversa.getPagamentoEscolhido()) {
            case "pix" -> "Pix";
            case "credito" -> "crédito";
            case "debito" -> "débito";
            case "dinheiro" -> "dinheiro";
            default -> throw new IllegalArgumentException("Forma de pagamento inválida");
        };
        return "Ok" + vocativo(referencia) + ", vou mudar a forma de pagamento para " + forma
                + " e enviar o pedido à cozinha após sua confirmação."
                + "\n\n" + montarResumo(conversa, restaurante)
                + "\n\nConfira os itens, endereço e pagamento. Está tudo certo? Responda *sim* para confirmar."
                + ("pix".equals(conversa.getPagamentoEscolhido())
                        ? " Depois envio a chave Pix da loja e peço o comprovante." : "");
    }

    private String vocativo(Pedido pedido) {
        if (pedido == null || pedido.getCliente() == null) return "";
        String nome = vazio(pedido.getCliente().getNome()).trim();
        if (nome.isEmpty()) return "";
        String primeiro = nome.split("\\s+")[0].replaceAll("[^\\p{L}'-]", "");
        return primeiro.isBlank() || primeiro.length() > 40 ? "" : ", " + primeiro;
    }

    private String enderecoCompleto(Pedido pedido) {
        var cliente = pedido.getCliente();
        StringBuilder s = new StringBuilder(vazio(cliente.getEnderecoRua()).trim())
                .append(", ").append(vazio(cliente.getEnderecoNumero()).trim());
        if (!vazio(cliente.getEnderecoComplemento()).isBlank())
            s.append(" - ").append(cliente.getEnderecoComplemento().trim());
        s.append("\nBairro: ").append(vazio(cliente.getEnderecoBairro()).trim());
        s.append("\nCidade: ").append(vazio(cliente.getEnderecoCidade()).trim());
        if (!vazio(cliente.getEnderecoEstado()).isBlank()) s.append("/").append(cliente.getEnderecoEstado().trim());
        s.append("\nCEP: ").append(vazio(cliente.getEnderecoCep()).trim());
        if (!vazio(cliente.getEnderecoReferencia()).isBlank())
            s.append("\nReferência: ").append(cliente.getEnderecoReferencia().trim());
        return s.toString();
    }

    private Collection<String> variantesTelefone(String telefone) {
        String n = telefone.replaceAll("\\D", "");
        Set<String> out = new LinkedHashSet<>();
        if (n.length() >= 10 && n.length() <= 15) out.add(n);
        if (n.startsWith("55") && n.length() >= 12) out.add(n.substring(2));
        else if ((n.length() == 10 || n.length() == 11)) out.add("55" + n);
        return out;
    }

    private boolean temEndereco(com.mydelivery.model.Cliente c) {
        return c != null && c.getEnderecoRua() != null && !c.getEnderecoRua().isBlank()
                && c.getEnderecoNumero() != null && !c.getEnderecoNumero().isBlank()
                && c.getEnderecoBairro() != null && !c.getEnderecoBairro().isBlank()
                && c.getEnderecoCidade() != null && !c.getEnderecoCidade().isBlank()
                && c.getEnderecoCep() != null && !c.getEnderecoCep().isBlank();
    }
    private boolean positivaRepeticao(String t) { return positiva(t) || t.contains("repete") || t.contains("repetir") || t.contains("igual") || t.contains("mesmo pedido") || t.contains("manda o mesmo") || t.contains("ultimo pedido") || t.contains("último pedido") || t.startsWith("quero") || t.startsWith("pode"); }
    private boolean positiva(String t) { return Set.of("sim", "quero", "pode", "isso", "isso mesmo", "confirmo", "pode fazer", "pode mandar", "ok", "esta certo", "está certo", "sim confirma", "sim confirmo", "pode confirmar").contains(t) || t.startsWith("sim ") || t.startsWith("confirmo "); }
    private boolean negativa(String t) { return t.equals("nao") || t.equals("não") || t.startsWith("nao ") || t.startsWith("não ") || t.contains("agora nao") || t.contains("agora não") || t.contains("prefiro ver o cardapio") || t.contains("quero escolher"); }
    private boolean corrigirEscolha(String t) { return t.contains("apertei errado") || t.contains("cliquei errado") || t.contains("escolhi errado") || t.equals("errei"); }
    private boolean negouFormaPagamento(String t) {
        return t.startsWith("nao ") && (t.contains("pagar") || t.contains("pagamento"))
                && FORMA_PAGAMENTO.matcher(t).find();
    }
    private boolean confirmacaoFinal(String t) { return positiva(t) || t.contains("confirma") || t.contains("pode confirmar") || t.contains("esta certo") || t.contains("está certo"); }
    private boolean pediuHumano(String t) { return t.contains("atendente") || t.contains("humano") || t.contains("falar com alguem") || t.contains("falar com alguém"); }
    private boolean querMudarEndereco(String t) { return t.contains("mudar endereco") || t.contains("outro endereco") || t.contains("outro endereço") || t.contains("trocar endereco"); }
    private boolean solicitouPagamentoOnline(String t) {
        return t.contains("pix online") || t.contains("cartao online")
                || t.contains("cartão online") || t.contains("pagar online") || t.contains("pagamento online");
    }
    private boolean formaCartaoAmbigua(String t) {
        return (t.contains("cartao") || t.contains("cartão"))
                && !t.contains("credito") && !t.contains("crédito") && !t.contains("debito") && !t.contains("débito")
                && !solicitouPagamentoOnline(t) && detectarPagamento(t) == null;
    }
    private boolean solicitouAlteracaoItens(String t) {
        return t.contains(" sem ") || t.startsWith("sem ") || t.contains("tira ") || t.contains("tirar ")
                || t.contains("remove ") || t.contains("remover ") || t.contains("adiciona ")
                || t.contains("adicionar ") || t.contains("coloca mais")
                || t.contains("troca o sabor") || t.contains("trocar o produto") || t.contains("troca o produto")
                || ((t.contains("troca ") || t.contains("trocar "))
                        && !t.contains("pagamento") && !t.contains("pagar") && detectarPagamento(t) == null)
                || t.matches(".*\\b[2-9]\\s*x\\b.*") || t.contains("dois ") || t.contains("duas ");
    }
    private boolean enderecoBateComPedido(Pedido p) {
        var c = p.getCliente();
        String salvo = normalizar(p.getEnderecoEntrega());
        return salvo.contains(normalizar(vazio(c.getEnderecoRua())))
                && salvo.contains(normalizar(vazio(c.getEnderecoNumero())))
                && salvo.contains(normalizar(vazio(c.getEnderecoBairro())));
    }
    private String detectarPagamento(String t) {
        if (t.contains("pix online") || t.contains("cartao online") || t.contains("cartão online")) return null;
        Matcher intencao = INTENCAO_PAGAMENTO.matcher(t);
        String escolhida = null;
        while (intencao.find()) {
            String antes = t.substring(Math.max(0, intencao.start() - 4), intencao.start());
            if (!antes.endsWith("nao ")) escolhida = intencao.group(1) == null ? intencao.group(2) : intencao.group(1);
        }
        if (escolhida == null) {
            Matcher forma = FORMA_PAGAMENTO.matcher(t);
            while (forma.find()) {
                String trechoAnterior = t.substring(0, forma.start());
                if (!trechoAnterior.matches("(?s).*\\b(?:nao|sem)\\s+(?:quero\\s+)?(?:pagar\\s+)?(?:o|no|na|com)?\\s*$"))
                    escolhida = forma.group(1);
            }
        }
        return "especie".equals(escolhida) ? "dinheiro" : escolhida;
    }
    private String rotuloPagamento(String p) { return switch (p) { case "pix" -> "Pix pela chave da loja (enviar comprovante)"; case "dinheiro" -> "Dinheiro"; case "credito" -> "Crédito na entrega"; case "debito" -> "Débito na entrega"; default -> p; }; }
    private String rotuloPagamentoAnterior(Pedido p) {
        if (p.getModoPagamento() == Pedido.ModoPagamento.ONLINE) return "pagamento online";
        return switch (p.getFormaPagamento()) {
            case PIX -> "Pix na entrega"; case DINHEIRO -> "Dinheiro"; case CARTAO_CREDITO -> "Crédito na entrega";
            case CARTAO_DEBITO -> "Débito na entrega"; case CARTAO_MAQUININHA -> "Cartão na entrega";
            default -> "não identificado";
        };
    }
    private String moeda(BigDecimal n) { return n.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ','); }
    public String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String gerarToken() {
        byte[] bytes = new byte[32]; SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private String vazio(String s) { return s == null ? "" : s; }
    private String normalizar(String s) { return s == null ? "" : java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " "); }
}
