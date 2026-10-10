package com.mydelivery.service.google;

import com.mydelivery.dto.mercadopago.MpPaymentResponse;
import com.mydelivery.model.*;
import com.mydelivery.repository.PagamentoMensalidadeRepository;
import com.mydelivery.service.mercadopago.MercadoPagoClient;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GoogleRevenueServiceTest {
    private PagamentoMensalidade payment() {
        var r=new Restaurante(); r.setId(42L); r.setGoogleClientId("123456.789012"); r.setGoogleMeasurementConsent(true); r.setGoogleConsentVersion(2); r.setGoogleConsentAt(java.time.Instant.parse("2000-01-01T00:00:00Z"));
        return PagamentoMensalidade.builder().restaurante(r).status(PagamentoMensalidade.Status.PAGO)
            .mpPaymentId(987654321L).valor(new BigDecimal("75.00")).plano(Plano.MENSAL)
            .pagoEm(LocalDateTime.of(2026,9,16,13,0)).build();
    }
    private MpPaymentResponse receipt() {
        var r=new MpPaymentResponse(); r.setId(987654321L); r.setStatus("approved");
        r.setDateApproved("2026-09-16T13:00:00.000-03:00"); r.setExternalReference("assinatura-42-MENSAL-123"); r.setTransactionAmount(new BigDecimal("75.00")); return r;
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> params(Map<String,Object> body) {
        return (Map<String,Object>) ((List<Map<String,Object>>)body.get("events")).get(0).get("params");
    }
    @Test void usesActualApprovedAmountAndStableAnonymousTransaction() throws Exception {
        var p=payment(); var r=receipt(); r.setTransactionAmount(new BigDecimal("63.50"));
        var first=GoogleRevenueService.payload(p,r); var second=GoogleRevenueService.payload(p,r);
        assertThat(params(first).get("value")).isEqualTo(new BigDecimal("63.50"));
        assertThat(params(first).get("currency")).isEqualTo("BRL");
        assertThat(params(first).get("transaction_id")).isEqualTo(params(second).get("transaction_id"));
        assertThat(first.toString()).doesNotContain("987654321", "email", "accessToken", "api_secret");
    }
    @Test void unpaidAndUnapprovedNeverBecomeRevenue() throws Exception {
        var p=payment(); p.setStatus(PagamentoMensalidade.Status.PENDENTE);
        assertThat(GoogleRevenueService.payload(p,receipt())).isNull();
        p.setStatus(PagamentoMensalidade.Status.PAGO);
        for(var status:List.of("pending","in_process","rejected","cancelled","refunded")) {
            var r=receipt(); r.setStatus(status); assertThat(GoogleRevenueService.payload(p,r)).isNull();
        }
    }
    @Test void excludesOtherMerchantOrPaymentAndInvalidRevenue() throws Exception {
        var p=payment(); var r=receipt(); r.setExternalReference("assinatura-420-MENSAL-123");
        assertThat(GoogleRevenueService.payload(p,r)).isNull();
        r=receipt(); r.setId(11L); assertThat(GoogleRevenueService.payload(p,r)).isNull();
        r=receipt(); r.setTransactionAmount(BigDecimal.ZERO); assertThat(GoogleRevenueService.payload(p,r)).isNull();
    }
    @Test void requiresConsentAndAnonymousClientId() throws Exception {
        var p=payment(); p.getRestaurante().setGoogleMeasurementConsent(false);
        assertThat(GoogleRevenueService.payload(p,receipt())).isNull();
        p.getRestaurante().setGoogleMeasurementConsent(true);
        for(String id:Arrays.asList(null,"", "private@example.com", "invalid")) {
            p.getRestaurante().setGoogleClientId(id); assertThat(GoogleRevenueService.payload(p,receipt())).isNull();
        }
    }
    @Test void usesGatewayTimestampWithItsOffsetRegardlessOfServerClock() throws Exception {
        var p=payment(); p.setPagoEm(LocalDateTime.of(2026,9,16,16,0)); var r=receipt();
        r.setDateApproved("2026-09-16T13:00:00.000-03:00");
        assertThat(GoogleRevenueService.payload(p,r).get("timestamp_micros"))
            .isEqualTo(java.time.Instant.parse("2026-09-16T16:00:00Z").toEpochMilli()*1000);
    }
    @Test void oldConsentDoesNotAuthorizePaidAttribution() throws Exception {
        var p=payment(); p.getRestaurante().setGoogleConsentVersion(null);
        assertThat(GoogleRevenueService.payload(p,receipt())).isNull();
    }
    @Test void consentMustPrecedeApprovalEvenWhenLocalPaymentTimeDiffers() throws Exception {
        var p=payment(); p.setPagoEm(LocalDateTime.of(2026,9,16,18,0));
        p.getRestaurante().setGoogleConsentAt(java.time.Instant.parse("2026-09-16T16:00:01Z"));
        assertThat(GoogleRevenueService.payload(p,receipt())).isNull();
        p.getRestaurante().setGoogleConsentAt(java.time.Instant.parse("2026-09-16T15:59:59Z"));
        assertThat(GoogleRevenueService.payload(p,receipt())).isNotNull();
    }
    @Test void staysDisabledWithoutServerCredential() {
        var repo=mock(PagamentoMensalidadeRepository.class); var mp=mock(MercadoPagoClient.class);
        new GoogleRevenueService(repo,mp,"","G-367LSZTGFW","mp-token").enviarPendentes();
        verifyNoInteractions(repo,mp);
    }
}
