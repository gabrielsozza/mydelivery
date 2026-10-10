package com.mydelivery.beneficios;

import com.mydelivery.dto.mercadopago.MpPaymentResponse;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.*;
import com.mydelivery.service.AssinaturaService;
import com.mydelivery.service.mercadopago.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AssinaturaEstornoWebhookTest {
    private final MercadoPagoClient client = mock(MercadoPagoClient.class);
    private final MercadoPagoSignatureValidator signature = mock(MercadoPagoSignatureValidator.class);
    private final RestauranteRepository restaurantes = mock(RestauranteRepository.class);
    private final AssinaturaService assinaturas = mock(AssinaturaService.class);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private MercadoPagoWebhookService service;
    private final MercadoPagoWebhookService.WebhookInput input =
            new MercadoPagoWebhookService.WebhookInput("event", "signature", "payment", "payment.updated", "123");

    @BeforeEach void setup() {
        service = new MercadoPagoWebhookService(mock(PagamentoRepository.class), mock(PedidoRepository.class),
                mock(ConfiguracaoRestauranteRepository.class), mock(MercadoPagoEventoProcessadoRepository.class),
                signature, client, restaurantes, assinaturas, "test-token", "test-secret");
        ReflectionTestUtils.setField(service, "beneficiosDb", db);
        when(signature.valido("signature", "event", "123", "test-secret")).thenReturn(true);
        Restaurante r = new Restaurante(); r.setId(7L);
        when(restaurantes.findById(7L)).thenReturn(Optional.of(r));
    }

    private void payment(String prefix, String status) {
        MpPaymentResponse response = new MpPaymentResponse();
        response.setExternalReference(prefix + "-7-MENSAL-12345");
        response.setStatus(status);
        when(client.consultar("test-token", 123L)).thenReturn(response);
    }

    @ParameterizedTest
    @CsvSource({"assinatura,refunded", "assinatura,charged_back", "renov,refunded", "renov,charged_back"})
    void estornoConfirmadoCancelaSomenteMensalidadeCorrespondente(String prefix, String status) {
        payment(prefix, status);
        assertEquals(MercadoPagoWebhookService.Resultado.OK, service.processar(input));
        verify(db).update("UPDATE pagamentos_mensalidade SET status='CANCELADO',mp_status_detail=? WHERE restaurante_id=? AND mp_payment_id=?",
                status, 7L, 123L);
        verifyNoInteractions(assinaturas);
    }

    @Test void renovacaoAprovadaContinuaNoFluxoExistenteSemAtivarDuasVezes() {
        payment("renov", "approved");
        assertEquals(MercadoPagoWebhookService.Resultado.OK, service.processar(input));
        verifyNoInteractions(db, assinaturas);
    }

    @Test void assinaturaInvalidaNaoAlteraMensalidade() {
        payment("renov", "refunded");
        when(signature.valido("signature", "event", "123", "test-secret")).thenReturn(false);
        assertEquals(MercadoPagoWebhookService.Resultado.INVALIDO, service.processar(input));
        verifyNoInteractions(db, assinaturas);
    }
}
