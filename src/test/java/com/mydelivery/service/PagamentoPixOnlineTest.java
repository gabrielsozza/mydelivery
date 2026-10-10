package com.mydelivery.service;

import com.mydelivery.model.*;
import com.mydelivery.repository.*;
import com.mydelivery.dto.pagamento.PagarPixRequest;
import com.mydelivery.dto.mercadopago.MpPaymentResponse;
import com.mydelivery.config.MercadoPagoProperties;
import com.mydelivery.service.mercadopago.*;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class PagamentoPixOnlineTest {
    @Mock PagamentoRepository pagamentos;
    @Mock PedidoRepository pedidos;
    @Mock ConfiguracaoRestauranteRepository configs;
    @Mock MercadoPagoClient mp;
    @InjectMocks PagamentoService service;
    Restaurante loja;
    Pedido pedido;
    ConfiguracaoRestaurante cfg;
    PagarPixRequest req;

    @BeforeEach void setup() {
        loja = Restaurante.builder().id(33L).nome("Loja").build();
        pedido = Pedido.builder().id(9055L).restaurante(loja).total(new BigDecimal("45.00"))
                .status(Pedido.Status.AGUARDANDO_PAGAMENTO).modoPagamento(Pedido.ModoPagamento.ONLINE)
                .formaPagamento(Pedido.FormaPagamento.PIX).build();
        cfg = ConfiguracaoRestaurante.builder().restaurante(loja).mpAccessToken("token-da-loja")
                .mpWebhookSecret("secret-da-loja").mpPublicKey("public-key").build();
        req = new PagarPixRequest(); req.setPedidoId(9055L); req.setPayerCpf("12345678909");
    }

    @Test void semConfiguracaoNaoChamaMercadoPago() {
        when(pedidos.findById(9055L)).thenReturn(Optional.of(pedido));
        assertThatThrownBy(() -> service.criarPagamentoPixOnline(req)).hasMessageContaining("sem configuração");
        verifyNoInteractions(mp);
    }

    @Test void semWebhookNaoOferecePixComConfirmacaoImpossivel() {
        cfg.setMpWebhookSecret(null);
        assertThat(MercadoPagoDisponibilidade.pix(cfg)).isFalse();
        when(pedidos.findById(9055L)).thenReturn(Optional.of(pedido));
        when(configs.findByRestauranteId(33L)).thenReturn(Optional.of(cfg));
        assertThatThrownBy(() -> service.criarPagamentoPixOnline(req)).hasMessageContaining("concluir a configuração");
        verifyNoInteractions(mp);
    }

    @Test void disponibilidadeDistinguePixDeCartao() {
        assertThat(MercadoPagoDisponibilidade.pix(null)).isFalse();
        assertThat(MercadoPagoDisponibilidade.cartao(cfg)).isTrue();
        cfg.setMpPublicKey(null);
        assertThat(MercadoPagoDisponibilidade.pix(cfg)).isTrue();
        assertThat(MercadoPagoDisponibilidade.cartao(cfg)).isFalse();
    }

    @Test void pedidoCanceladoNaoGeraCobranca() {
        pedido.setStatus(Pedido.Status.CANCELADO);
        when(pedidos.findById(9055L)).thenReturn(Optional.of(pedido));
        assertThatThrownBy(() -> service.criarPagamentoPixOnline(req)).hasMessageContaining("cancelado");
        verifyNoInteractions(mp, configs);
    }

    @Test void pedidoPresencialNaoPodeVirarCobrancaOnline() {
        pedido.setModoPagamento(Pedido.ModoPagamento.NA_ENTREGA);
        when(pedidos.findById(9055L)).thenReturn(Optional.of(pedido));
        assertThatThrownBy(() -> service.criarPagamentoPixOnline(req)).hasMessageContaining("não corresponde");
        verifyNoInteractions(mp);
    }

    @Test void criaQrComCredencialDaLojaEReaproveitaNoRetry() {
        when(pedidos.findById(9055L)).thenReturn(Optional.of(pedido));
        when(configs.findByRestauranteId(33L)).thenReturn(Optional.of(cfg));
        when(mp.props()).thenReturn(new MercadoPagoProperties());
        var resp = new MpPaymentResponse(); resp.setId(777L); resp.setStatus("pending");
        var td = new MpPaymentResponse.TransactionData(); td.setQrCode("codigo-pix"); td.setQrCodeBase64("imagem");
        var poi = new MpPaymentResponse.PointOfInteraction(); poi.setTransactionData(td); resp.setPointOfInteraction(poi);
        when(mp.criarPagamento(eq("token-da-loja"),eq("mydelivery-pedido-9055-pix"),any())).thenReturn(resp);
        when(pagamentos.save(any())).thenAnswer(i -> i.getArgument(0));
        var p = service.criarPagamentoPixOnline(req);
        assertThat(p.getPixBrCode()).isEqualTo("codigo-pix");
        assertThat(p.getMpPaymentId()).isEqualTo(777L);
        when(pagamentos.findByPedidoId(9055L)).thenReturn(Optional.of(p));
        assertThat(service.criarPagamentoPixOnline(req)).isSameAs(p);
        verify(mp,times(1)).criarPagamento(anyString(),anyString(),any());
    }

    @Test void respostaSemQrNaoReutilizaPixEstaticoAntigo() {
        when(pedidos.findById(9055L)).thenReturn(Optional.of(pedido));
        when(configs.findByRestauranteId(33L)).thenReturn(Optional.of(cfg));
        when(mp.props()).thenReturn(new MercadoPagoProperties());
        var p = Pagamento.builder().pedido(pedido).pixBrCode("estatico-antigo").status(Pagamento.Status.PENDENTE).build();
        when(pagamentos.findByPedidoId(9055L)).thenReturn(Optional.of(p));
        var resp = new MpPaymentResponse(); resp.setId(777L); resp.setStatus("pending");
        when(mp.criarPagamento(anyString(),anyString(),any())).thenReturn(resp);
        assertThatThrownBy(() -> service.criarPagamentoPixOnline(req)).hasMessageContaining("gerar o código Pix");
        verify(pagamentos, never()).save(any());
    }
}
