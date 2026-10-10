package com.mydelivery.service.mercadopago;

import com.mydelivery.model.*;
import com.mydelivery.repository.*;
import com.mydelivery.dto.mercadopago.MpPaymentResponse;
import com.mydelivery.service.AssinaturaService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MercadoPagoConfirmacaoTest {
    @Test void atualizacaoPendenteSeguidaDeAprovacaoConfirmaPedidoUmaVez() {
        var pagamentos=mock(PagamentoRepository.class);
        var pedidos=mock(PedidoRepository.class);
        var configs=mock(ConfiguracaoRestauranteRepository.class);
        var eventos=mock(MercadoPagoEventoProcessadoRepository.class);
        var assinatura=mock(MercadoPagoSignatureValidator.class);
        var mp=mock(MercadoPagoClient.class);
        var service=new MercadoPagoWebhookService(pagamentos,pedidos,configs,eventos,assinatura,mp,
                mock(RestauranteRepository.class),mock(AssinaturaService.class),"","");
        var loja=Restaurante.builder().id(33L).build();
        var pedido=Pedido.builder().id(9055L).restaurante(loja).status(Pedido.Status.AGUARDANDO_PAGAMENTO).pago(false).build();
        var pagamento=Pagamento.builder().pedido(pedido).mpPaymentId(777L).status(Pagamento.Status.PENDENTE).build();
        when(pagamentos.findByMpPaymentId(777L)).thenReturn(Optional.of(pagamento));
        when(configs.findByRestauranteId(33L)).thenReturn(Optional.of(ConfiguracaoRestaurante.builder()
                .mpAccessToken("token-loja").mpWebhookSecret("secret").build()));
        when(assinatura.valido(any(),any(),any(),eq("secret"))).thenReturn(true);
        var pending=new MpPaymentResponse();pending.setStatus("pending");
        var approved=new MpPaymentResponse();approved.setStatus("approved");
        when(mp.consultar("token-loja",777L)).thenReturn(pending,approved);
        service.processar(new MercadoPagoWebhookService.WebhookInput("evt-1","sig","payment","payment.updated","777"));
        assertThat(pedido.getPago()).isFalse();
        assertThat(pedido.getStatus()).isEqualTo(Pedido.Status.AGUARDANDO_PAGAMENTO);
        var evento=new MercadoPagoWebhookService.WebhookInput("evt-2","sig","payment","payment.updated","777");
        service.processar(evento);
        assertThat(pedido.getPago()).isTrue();
        assertThat(pedido.getStatus()).isEqualTo(Pedido.Status.PENDENTE);
        assertThat(pagamento.getStatus()).isEqualTo(Pagamento.Status.APROVADO);
        when(eventos.existsById("evt-2")).thenReturn(true);
        service.processar(evento);
        verify(mp,times(2)).consultar("token-loja",777L);
    }
}
