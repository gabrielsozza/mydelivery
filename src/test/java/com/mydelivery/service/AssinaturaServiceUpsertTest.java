package com.mydelivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.mydelivery.model.PagamentoMensalidade;
import com.mydelivery.model.Plano;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.AssinaturaRepository;
import com.mydelivery.repository.PagamentoMensalidadeRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.meta.MetaCapiService;

/**
 * Cobre o upsert de {@code PagamentoMensalidade} PENDENTE → PAGO quando o webhook
 * confirma o PIX. Regressão do bug em que o painel Admin não via faturamento.
 *
 * O comportamento correto tem 3 caminhos:
 *   a) PENDENTE preexistente → é promovido a PAGO (não cria nova linha).
 *   b) Nada existente → cria nova linha PAGO.
 *   c) Já PAGO (webhook reentregue) → no-op, não duplica.
 */
class AssinaturaServiceUpsertTest {

    @Test
    void promove_pendente_existente_em_pago_sem_criar_nova_linha() {
        // Arrange — mocks das dependências
        AssinaturaRepository assinaturaRepo = mock(AssinaturaRepository.class);
        RestauranteRepository restauranteRepo = mock(RestauranteRepository.class);
        PlanoCatalogoService planoCatalogoService = mock(PlanoCatalogoService.class);
        MetaCapiService metaCapi = mock(MetaCapiService.class);
        PagamentoMensalidadeRepository pmRepo = mock(PagamentoMensalidadeRepository.class);
        EmailService emailService = mock(EmailService.class);

        long mpPaymentId = 12345L;
        Restaurante r = new Restaurante();
        r.setId(1L);
        when(planoCatalogoService.valorAtual(Plano.MENSAL)).thenReturn(new BigDecimal("99.90"));

        // Linha PENDENTE preexistente (criada em criarPix)
        PagamentoMensalidade preexistente = PagamentoMensalidade.builder()
                .id(500L)
                .restaurante(r)
                .valor(new BigDecimal("99.90"))
                .status(PagamentoMensalidade.Status.PENDENTE)
                .mpPaymentId(mpPaymentId)
                .metodoPagamento("PIX_MP")
                .plano(Plano.MENSAL)
                .build();
        when(pmRepo.existsByMpPaymentIdAndStatus(eq(mpPaymentId), eq(PagamentoMensalidade.Status.PAGO)))
                .thenReturn(false);
        when(pmRepo.findByMpPaymentId(mpPaymentId)).thenReturn(Optional.of(preexistente));

        AssinaturaService service = new AssinaturaService(
                assinaturaRepo, restauranteRepo, planoCatalogoService, metaCapi, pmRepo, emailService);

        // Act
        LocalDateTime pagoEm = LocalDateTime.of(2026, 7, 12, 10, 0);
        service.registrarPagamentoOk(r, Plano.MENSAL, "PIX_MP", mpPaymentId, pagoEm);

        // Assert — a mesma instância pré-existente foi persistida com status PAGO,
        // sem criar linha nova.
        ArgumentCaptor<PagamentoMensalidade> captor = ArgumentCaptor.forClass(PagamentoMensalidade.class);
        verify(pmRepo).save(captor.capture());
        PagamentoMensalidade salvo = captor.getValue();
        assertThat(salvo.getId()).isEqualTo(500L);
        assertThat(salvo.getStatus()).isEqualTo(PagamentoMensalidade.Status.PAGO);
        assertThat(salvo.getPagoEm()).isEqualTo(pagoEm);
        assertThat(salvo.getMpPaymentId()).isEqualTo(mpPaymentId);
    }

    @Test
    void cria_nova_linha_pago_quando_nao_existe_pendente() {
        AssinaturaRepository assinaturaRepo = mock(AssinaturaRepository.class);
        RestauranteRepository restauranteRepo = mock(RestauranteRepository.class);
        PlanoCatalogoService planoCatalogoService = mock(PlanoCatalogoService.class);
        MetaCapiService metaCapi = mock(MetaCapiService.class);
        PagamentoMensalidadeRepository pmRepo = mock(PagamentoMensalidadeRepository.class);
        EmailService emailService = mock(EmailService.class);

        long mpPaymentId = 22222L;
        Restaurante r = new Restaurante();
        r.setId(2L);
        when(planoCatalogoService.valorAtual(Plano.SEMESTRAL)).thenReturn(new BigDecimal("499.00"));
        when(pmRepo.existsByMpPaymentIdAndStatus(anyLong(), any())).thenReturn(false);
        when(pmRepo.findByMpPaymentId(mpPaymentId)).thenReturn(Optional.empty());

        AssinaturaService service = new AssinaturaService(
                assinaturaRepo, restauranteRepo, planoCatalogoService, metaCapi, pmRepo, emailService);

        service.registrarPagamentoOk(r, Plano.SEMESTRAL, "PIX_MP", mpPaymentId, null);

        ArgumentCaptor<PagamentoMensalidade> captor = ArgumentCaptor.forClass(PagamentoMensalidade.class);
        verify(pmRepo).save(captor.capture());
        PagamentoMensalidade salvo = captor.getValue();
        assertThat(salvo.getId()).isNull(); // nova linha, ainda sem id
        assertThat(salvo.getStatus()).isEqualTo(PagamentoMensalidade.Status.PAGO);
        assertThat(salvo.getMpPaymentId()).isEqualTo(mpPaymentId);
        assertThat(salvo.getPlano()).isEqualTo(Plano.SEMESTRAL);
    }

    @Test
    void nao_duplica_quando_ja_existe_pago_para_o_mesmo_mpPaymentId() {
        AssinaturaRepository assinaturaRepo = mock(AssinaturaRepository.class);
        RestauranteRepository restauranteRepo = mock(RestauranteRepository.class);
        PlanoCatalogoService planoCatalogoService = mock(PlanoCatalogoService.class);
        MetaCapiService metaCapi = mock(MetaCapiService.class);
        PagamentoMensalidadeRepository pmRepo = mock(PagamentoMensalidadeRepository.class);
        EmailService emailService = mock(EmailService.class);

        long mpPaymentId = 33333L;
        Restaurante r = new Restaurante();
        r.setId(3L);
        // Cenário: webhook chegou duas vezes; a primeira já registrou como PAGO.
        when(pmRepo.existsByMpPaymentIdAndStatus(
                eq(mpPaymentId), eq(PagamentoMensalidade.Status.PAGO))).thenReturn(true);

        AssinaturaService service = new AssinaturaService(
                assinaturaRepo, restauranteRepo, planoCatalogoService, metaCapi, pmRepo, emailService);

        service.registrarPagamentoOk(r, Plano.ANUAL, "PIX_MP", mpPaymentId, null);

        // Nenhum save deve ocorrer — a duplicata é filtrada.
        verify(pmRepo, never()).save(any());
        verify(pmRepo, never()).findByMpPaymentId(anyLong());
    }
}
