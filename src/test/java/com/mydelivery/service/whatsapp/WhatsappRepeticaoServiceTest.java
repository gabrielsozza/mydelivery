package com.mydelivery.service.whatsapp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.mydelivery.model.Cliente;
import com.mydelivery.model.ConfiguracaoRestaurante;
import com.mydelivery.model.ComplementoGrupo;
import com.mydelivery.model.ComplementoItem;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.PedidoItem;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.model.WhatsappRepeticaoConversa;
import com.mydelivery.dto.pedido.NovoPedidoRequest;
import com.mydelivery.dto.pedido.PedidoResponse;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.repository.WhatsappRepeticaoConversaRepository;

class WhatsappRepeticaoServiceTest {
    private WhatsappRepeticaoConversaRepository conversas;
    private PedidoRepository pedidos;
    private ConfiguracaoRestauranteRepository configs;
    private RestauranteRepository restaurantes;
    private com.mydelivery.repository.ComplementoGrupoRepository grupos;
    private com.mydelivery.service.PedidoService pedidoService;
    private WhatsappRepeticaoService service;

    @BeforeEach
    void setup() {
        conversas = mock(WhatsappRepeticaoConversaRepository.class);
        pedidos = mock(PedidoRepository.class);
        configs = mock(ConfiguracaoRestauranteRepository.class);
        restaurantes = mock(RestauranteRepository.class);
        grupos = mock(com.mydelivery.repository.ComplementoGrupoRepository.class);
        pedidoService = mock(com.mydelivery.service.PedidoService.class);
        service = new WhatsappRepeticaoService(conversas, pedidos, configs, restaurantes, grupos, pedidoService);
    }

    @Test
    void recursoDesativadoNaoConsultaHistorico() {
        Restaurante loja = new Restaurante();
        loja.setId(7L);
        loja.setRepeticaoInteligenteWhatsapp(false);

        assertNull(service.processar(loja, "5511999999999", "oi", "https://mydeliveryfood.com.br/loja"));
        verifyNoInteractions(conversas, pedidos);
    }

    @Test
    void ofertaFicaRestritaAoTelefoneELojaEUsaLinkOpaco() {
        Restaurante loja = new Restaurante();
        loja.setId(7L);
        loja.setRepeticaoInteligenteWhatsapp(true);
        loja.setNome("Loja Teste");
        Cliente cliente = new Cliente();
        cliente.setTelefone("5511999999999");
        cliente.setNome("Cliente");
        cliente.setEnderecoRua("Rua Particular");
        cliente.setEnderecoNumero("123");
        cliente.setEnderecoBairro("Centro");
        cliente.setEnderecoCidade("São Paulo");
        cliente.setEnderecoEstado("SP");
        cliente.setEnderecoCep("01001-000");
        Pedido referencia = new Pedido();
        referencia.setId(42L);
        referencia.setRestaurante(loja);
        referencia.setCliente(cliente);
        referencia.setTipo(Pedido.Tipo.DELIVERY);
        referencia.setStatus(Pedido.Status.ENTREGUE);
        referencia.setFormaPagamento(Pedido.FormaPagamento.DINHEIRO);
        referencia.setTotal(new BigDecimal("25.00"));
        Produto produto = new Produto();
        produto.setId(3L);
        produto.setNome("X-Burger");
        produto.setDisponivel(true);
        produto.setRestaurante(loja);
        PedidoItem item = new PedidoItem();
        item.setProduto(produto);
        item.setNomeProduto("X-Burger");
        item.setQuantidade(1);
        item.setPrecoUnitario(new BigDecimal("25.00"));
        referencia.setItens(List.of(item));

        when(pedidos.findPedidosRepetiveis(eq(7L), anyCollection(), anyCollection(), anyCollection()))
                .thenReturn(List.of(referencia));
        when(conversas.findByRestauranteIdAndTelefoneHashAndPedidoReferenciaId(7L,
                service.hashToken("5511999999999"), 42L))
                .thenReturn(Optional.empty());
        when(conversas.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        String resposta = service.processar(loja, "5511999999999@s.whatsapp.net", "oi",
                "https://mydeliveryfood.com.br/loja");

        assertNotNull(resposta);
        assertTrue(resposta.contains("1x X-Burger"));
        assertTrue(resposta.contains("Olá, Cliente!"));
        assertTrue(resposta.contains("Rua Particular, 123"));
        assertTrue(resposta.contains("Bairro: Centro"));
        assertTrue(resposta.contains("Cidade: São Paulo/SP"));
        assertTrue(resposta.contains("CEP: 01001-000"));
        assertTrue(resposta.contains("repeticao="));
        ArgumentCaptor<WhatsappRepeticaoConversa> captor = ArgumentCaptor.forClass(WhatsappRepeticaoConversa.class);
        verify(conversas).saveAndFlush(captor.capture());
        assertEquals(7L, captor.getValue().getRestaurante().getId());
        assertEquals(service.hashToken("5511999999999"), captor.getValue().getTelefoneHash());
        assertEquals(64, captor.getValue().getLinkTokenHash().length());
    }

    private record Cenario(Restaurante loja, Pedido referencia, WhatsappRepeticaoConversa conversa) {}

    private Cenario cenarioAtivo() {
        Restaurante loja = new Restaurante();
        loja.setId(7L);
        loja.setSlug("loja");
        loja.setRepeticaoInteligenteWhatsapp(true);
        loja.setAberto(true);
        loja.setChavePixAntecipado("chave-da-loja");
        Cliente cliente = new Cliente();
        cliente.setNome("Ana Silva");
        cliente.setTelefone("5511999999999");
        cliente.setEnderecoRua("Rua Nova");
        cliente.setEnderecoNumero("20");
        cliente.setEnderecoBairro("Centro");
        cliente.setEnderecoCidade("São Paulo");
        cliente.setEnderecoEstado("SP");
        cliente.setEnderecoCep("01001-000");
        Pedido referencia = new Pedido();
        referencia.setId(42L);
        referencia.setRestaurante(loja);
        referencia.setCliente(cliente);
        referencia.setTipo(Pedido.Tipo.DELIVERY);
        referencia.setStatus(Pedido.Status.ENTREGUE);
        referencia.setFormaPagamento(Pedido.FormaPagamento.DINHEIRO);
        referencia.setModoPagamento(Pedido.ModoPagamento.NA_ENTREGA);
        Produto produto = new Produto();
        produto.setId(3L);
        produto.setRestaurante(loja);
        produto.setNome("Pizza");
        produto.setDisponivel(true);
        produto.setPreco(new BigDecimal("40.00"));
        PedidoItem item = new PedidoItem();
        item.setProduto(produto);
        item.setQuantidade(1);
        item.setObservacao("sem cebola");
        referencia.setItens(List.of(item));
        WhatsappRepeticaoConversa conversa = new WhatsappRepeticaoConversa();
        conversa.setId(11L);
        conversa.setRestaurante(loja);
        conversa.setPedidoReferencia(referencia);
        conversa.setEstado(WhatsappRepeticaoConversa.Estado.OFERTA_DE_REPETICAO);
        ConfiguracaoRestaurante config = new ConfiguracaoRestaurante();
        config.setAceitaPix(true);
        config.setAceitaDinheiro(true);
        config.setAceitaCartaoMaquininha(true);
        when(configs.findByRestauranteId(7L)).thenReturn(Optional.of(config));
        when(restaurantes.findById(7L)).thenReturn(Optional.of(loja));
        when(conversas.findTopByRestauranteIdAndTelefoneHashAndExpiraEmAfterOrderByIdDesc(eq(7L), anyString(), any()))
                .thenReturn(Optional.of(conversa));
        when(conversas.findByIdForUpdate(11L)).thenReturn(Optional.of(conversa));
        when(grupos.findByProdutoIdOrderByIdAsc(3L)).thenReturn(List.of());
        when(pedidoService.calcularTaxaEntrega(eq(loja), anyMap())).thenReturn(BigDecimal.ZERO);
        return new Cenario(loja, referencia, conversa);
    }

    @Test
    void trocaQualquerFormaConfirmaAntesDeCriarPedido() {
        String[][] casos = { {"Quero pagar no pix", "pix", "Pix"},
                {"Quero pagar no crédito", "credito", "crédito"},
                {"Quero pagar no débito", "debito", "débito"},
                {"Quero pagar em dinheiro", "dinheiro", "dinheiro"} };
        for (String[] caso : casos) {
            Cenario c = cenarioAtivo();
            String resposta = service.processar(c.loja(), "5511999999999", caso[0], "https://mydeliveryfood.com.br/loja");
            assertTrue(resposta.contains("Ok, Ana, vou mudar a forma de pagamento para " + caso[2]), resposta);
            assertTrue(resposta.contains("Rua Nova, 20"), resposta);
            assertTrue(resposta.contains("sem cebola"), resposta);
            assertEquals(caso[1], c.conversa().getPagamentoEscolhido());
            assertEquals(WhatsappRepeticaoConversa.Estado.AGUARDANDO_CONFIRMACAO_FINAL, c.conversa().getEstado());
        }
        verify(pedidoService, never()).criarPedidoViaWhatsappRepeticao(any(), anyLong());
    }

    @Test
    void pixCriaPedidoNormalEEnviaChavePedindoComprovante() {
        Cenario c = cenarioAtivo();
        String pergunta = service.processar(c.loja(), "5511999999999", "Quero pagar no pix", "https://mydeliveryfood.com.br/loja");
        assertTrue(pergunta.contains("Responda *sim*"));
        Pedido criado = new Pedido();
        criado.setId(99L);
        criado.setTotal(new BigDecimal("40.00"));
        when(pedidoService.criarPedidoViaWhatsappRepeticao(any(), eq(11L)))
                .thenReturn(PedidoResponse.builder().id(99L).build());
        when(pedidos.findById(99L)).thenReturn(Optional.of(criado));

        String confirmacao = service.processar(c.loja(), "5511999999999", "sim", "https://mydeliveryfood.com.br/loja");

        assertTrue(confirmacao.contains("Chave Pix da loja: *chave-da-loja*"), confirmacao);
        assertTrue(confirmacao.contains("envie o comprovante"), confirmacao);
        ArgumentCaptor<NovoPedidoRequest> pedidoEnviado = ArgumentCaptor.forClass(NovoPedidoRequest.class);
        verify(pedidoService).criarPedidoViaWhatsappRepeticao(pedidoEnviado.capture(), eq(11L));
        assertEquals("pix", pedidoEnviado.getValue().getPagamento());
        assertEquals("entrega", pedidoEnviado.getValue().getModoPagamento());
        assertEquals("Rua Nova", pedidoEnviado.getValue().getEndereco().get("rua"));
    }

    @Test
    void pagamentoPodeMudarNoResumoSemDesviarParaCardapio() {
        Cenario c = cenarioAtivo();
        service.processar(c.loja(), "5511999999999", "Quero pagar no crédito", "https://mydeliveryfood.com.br/loja");
        String resposta = service.processar(c.loja(), "5511999999999", "Apertei errado, quero pagar no débito", "https://mydeliveryfood.com.br/loja");
        assertTrue(resposta.contains("mudar a forma de pagamento para débito"), resposta);
        assertEquals("debito", c.conversa().getPagamentoEscolhido());
        verify(pedidoService, never()).criarPedidoViaWhatsappRepeticao(any(), anyLong());
    }

    @Test
    void refazComplementosPeloPrecoAtualEPreservaObservacoes() {
        Cenario c = cenarioAtivo();
        ComplementoGrupo grupo = new ComplementoGrupo();
        grupo.setId(30L);
        grupo.setModoPreco(ComplementoGrupo.ModoPreco.SOMA);
        grupo.setMaxEscolhas(3);
        ComplementoItem bacon = new ComplementoItem();
        bacon.setId(31L);
        bacon.setNome("Bacon");
        bacon.setPrecoAdicional(new BigDecimal("6.00"));
        bacon.setAtivo(true);
        grupo.setItens(List.of(bacon));
        when(grupos.findByProdutoIdOrderByIdAsc(3L)).thenReturn(List.of(grupo));
        c.referencia().getItens().getFirst().setObservacao("sem cebola\n+ Bacon ×2 (R$ 10,00)");

        String resumo = service.processar(c.loja(), "5511999999999", "Quero pagar no débito", "https://mydeliveryfood.com.br/loja");
        assertTrue(resumo.contains("Total: R$ 52,00"), resumo);
        assertTrue(resumo.contains("sem cebola; + Bacon ×2"), resumo);

        Pedido criado = new Pedido();
        criado.setId(99L);
        criado.setTotal(new BigDecimal("52.00"));
        when(pedidoService.criarPedidoViaWhatsappRepeticao(any(), eq(11L)))
                .thenReturn(PedidoResponse.builder().id(99L).build());
        when(pedidos.findById(99L)).thenReturn(Optional.of(criado));
        service.processar(c.loja(), "5511999999999", "sim", "https://mydeliveryfood.com.br/loja");

        ArgumentCaptor<NovoPedidoRequest> captor = ArgumentCaptor.forClass(NovoPedidoRequest.class);
        verify(pedidoService).criarPedidoViaWhatsappRepeticao(captor.capture(), eq(11L));
        var item = captor.getValue().getItens().getFirst();
        assertEquals(0, new BigDecimal("52.00").compareTo(item.getPreco()));
        assertEquals("sem cebola\n+ Bacon ×2", item.getObs());
        assertEquals(31L, item.getComplementos().getFirst().getItemId());
        assertEquals(2, item.getComplementos().getFirst().getQuantidade());
    }

    @Test
    void naoOferecePixSemChave() {
        Cenario c = cenarioAtivo();
        c.loja().setChavePixAntecipado(null);
        String resposta = service.processar(c.loja(), "5511999999999", "Quero pagar no Pix", "https://mydeliveryfood.com.br/loja");
        assertTrue(resposta.contains("não cadastrou uma chave Pix"), resposta);
        verify(pedidoService, never()).criarPedidoViaWhatsappRepeticao(any(), anyLong());
    }

    @Test
    void pedidoEmCartaoSemTipoPedeCreditoOuDebito() {
        Cenario c = cenarioAtivo();
        String resposta = service.processar(c.loja(), "5511999999999", "Quero pagar no cartão", "https://mydeliveryfood.com.br/loja");
        assertEquals("Você prefere crédito ou débito na entrega?", resposta);
        assertEquals(WhatsappRepeticaoConversa.Estado.VALIDANDO_ENDERECO, c.conversa().getEstado());
    }

    @Test
    void negacaoDoPixNaoConfirmaPixNemCancelaPedido() {
        Cenario c = cenarioAtivo();
        String resposta = service.processar(c.loja(), "5511999999999", "Não quero pagar no Pix", "https://mydeliveryfood.com.br/loja");
        assertTrue(resposta.contains("Qual forma você prefere"), resposta);
        assertNull(c.conversa().getPagamentoEscolhido());
        assertEquals(WhatsappRepeticaoConversa.Estado.VALIDANDO_ENDERECO, c.conversa().getEstado());
    }

    @Test
    void mensagemSobreComprovanteDepoisDaCriacaoNaoOfereceOutroPedido() {
        Cenario c = cenarioAtivo();
        c.conversa().setEstado(WhatsappRepeticaoConversa.Estado.PEDIDO_CRIADO);
        Pedido criado = new Pedido();
        criado.setId(99L);
        c.conversa().setPedidoCriado(criado);
        String resposta = service.processar(c.loja(), "5511999999999", "Enviei o comprovante", "https://mydeliveryfood.com.br/loja");
        assertTrue(resposta.contains("equipe da loja vai conferir"), resposta);
        assertNull(service.processar(c.loja(), "5511999999999", "Qual o prazo de entrega?", "https://mydeliveryfood.com.br/loja"));
    }

    @Test
    void conversaAntigaDesviadaPodeRetomarComPedidoExplicitoDePix() {
        Cenario c = cenarioAtivo();
        c.conversa().setEstado(WhatsappRepeticaoConversa.Estado.ENCAMINHADO_CARDAPIO);
        String resposta = service.processar(c.loja(), "5511999999999", "Quero pagar no Pix", "https://mydeliveryfood.com.br/loja");
        assertTrue(resposta.contains("mudar a forma de pagamento para Pix"), resposta);
        assertEquals(WhatsappRepeticaoConversa.Estado.AGUARDANDO_CONFIRMACAO_FINAL, c.conversa().getEstado());
    }
}
