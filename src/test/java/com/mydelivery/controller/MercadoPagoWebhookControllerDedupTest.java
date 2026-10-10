package com.mydelivery.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import com.mydelivery.service.mercadopago.MercadoPagoWebhookService;
import com.mydelivery.service.mercadopago.MercadoPagoWebhookService.Resultado;

class MercadoPagoWebhookControllerDedupTest {
    @Test void duasAtualizacoesDoMesmoPagamentoChegamAoServico() {
        var service = mock(MercadoPagoWebhookService.class);
        when(service.processar(any())).thenReturn(Resultado.OK);
        var ctrl = new MercadoPagoWebhookController(service);
        ctrl.receber(null, "req-1", "payment", "999", "payment.updated", null);
        ctrl.receber(null, "req-2", "payment", "999", "payment.updated", null);
        verify(service, times(2)).processar(any());
    }

    @Test void erroNaoBloqueiaReentregaDoEvento() {
        var service = mock(MercadoPagoWebhookService.class);
        when(service.processar(any())).thenReturn(Resultado.ERRO, Resultado.OK);
        var ctrl = new MercadoPagoWebhookController(service);
        assertThat(ctrl.receber(null, "req-1", "payment", "999", "payment.updated", null).getStatusCode().value()).isEqualTo(500);
        assertThat(ctrl.receber(null, "req-1", "payment", "999", "payment.updated", null).getStatusCode().value()).isEqualTo(200);
        verify(service, times(2)).processar(any());
    }

    @Test void assinaturaInvalidaNaoBloqueiaEventoLegitimoPosterior() {
        var service = mock(MercadoPagoWebhookService.class);
        when(service.processar(any())).thenReturn(Resultado.INVALIDO, Resultado.OK);
        var ctrl = new MercadoPagoWebhookController(service);
        assertThat(ctrl.receber(null, "req-1", "payment", "999", "payment.updated", null).getStatusCode().value()).isEqualTo(401);
        assertThat(ctrl.receber("valida", "req-2", "payment", "999", "payment.updated", null).getStatusCode().value()).isEqualTo(200);
        verify(service, times(2)).processar(any());
    }
}
