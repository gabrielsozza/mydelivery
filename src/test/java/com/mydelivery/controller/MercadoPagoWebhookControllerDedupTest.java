package com.mydelivery.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.mydelivery.service.WebhookDedupService;
import com.mydelivery.service.mercadopago.MercadoPagoWebhookService;
import com.mydelivery.service.mercadopago.MercadoPagoWebhookService.Resultado;
import com.mydelivery.service.mercadopago.MercadoPagoWebhookService.WebhookInput;

/**
 * Regressão do bug crítico onde a chave de dedup era {@code "type:dataId"} — o que
 * fazia o segundo webhook do MP para o MESMO paymentId (payment.updated após
 * payment.created) ser descartado como "duplicata", deixando o PIX aprovado nunca
 * ser processado. Agora a chave inclui {@code action}, e ambos os webhooks passam.
 */
class MercadoPagoWebhookControllerDedupTest {

    @Test
    void chaveDedup_inclui_action_e_permite_processar_created_e_updated_separadamente() {
        WebhookDedupService dedup = mock(WebhookDedupService.class);
        MercadoPagoWebhookService service = mock(MercadoPagoWebhookService.class);
        when(dedup.tryClaim(eq("mercadopago"), any())).thenReturn(true);
        when(service.processar(any())).thenReturn(Resultado.OK);

        MercadoPagoWebhookController ctrl = new MercadoPagoWebhookController(service, dedup);

        // 1º webhook — payment.created (status pending do MP)
        ctrl.receber(null, "req-1", "payment", "999", "payment.created", null);
        // 2º webhook — payment.updated (status approved)
        ctrl.receber(null, "req-2", "payment", "999", "payment.updated", null);

        ArgumentCaptor<String> chaves = ArgumentCaptor.forClass(String.class);
        verify(dedup, times(2)).tryClaim(eq("mercadopago"), chaves.capture());
        assertThat(chaves.getAllValues())
                .containsExactly("payment:999:payment.created", "payment:999:payment.updated");
        // Ambos webhooks precisam alcançar o service — o updated é onde o
        // status vai virar "approved" e ativar o plano.
        verify(service, times(2)).processar(any(WebhookInput.class));
    }

    @Test
    void reentrega_do_mesmo_event_action_ainda_e_deduplicada() {
        WebhookDedupService dedup = mock(WebhookDedupService.class);
        MercadoPagoWebhookService service = mock(MercadoPagoWebhookService.class);
        // Primeira chamada claim OK; segunda (mesma chave) retorna false → dedup
        when(dedup.tryClaim(eq("mercadopago"), eq("payment:999:payment.updated")))
                .thenReturn(true, false);
        when(service.processar(any())).thenReturn(Resultado.OK);

        MercadoPagoWebhookController ctrl = new MercadoPagoWebhookController(service, dedup);
        ctrl.receber(null, "req-1", "payment", "999", "payment.updated", null);
        ctrl.receber(null, "req-2", "payment", "999", "payment.updated", null);

        // Só uma vez o service é chamado — a segunda é filtrada como duplicata.
        verify(service, times(1)).processar(any(WebhookInput.class));
    }
}
