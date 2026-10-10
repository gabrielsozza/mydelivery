package com.mydelivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mydelivery.model.Assinatura;
import com.mydelivery.model.Plano;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.AssinaturaRepository;
import com.mydelivery.repository.PagamentoMensalidadeRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.meta.MetaCapiService;

class AssinaturaFiscalDowngradeTest {
    private final AssinaturaRepository assinaturas = mock(AssinaturaRepository.class);
    private final RestauranteRepository restaurantes = mock(RestauranteRepository.class);
    private final PlanoCatalogoService catalogo = mock(PlanoCatalogoService.class);
    private final AssinaturaService service = new AssinaturaService(assinaturas, restaurantes, catalogo,
            mock(MetaCapiService.class), mock(PagamentoMensalidadeRepository.class), mock(EmailService.class));

    @Test
    void confirmaEssencialSemCobrarNemAlterarVigencia() {
        Restaurante loja = new Restaurante();
        loja.setId(48L);
        loja.setFiscalHabilitado(true);
        loja.setValorMensalPersonalizado(new BigDecimal("99.90"));
        LocalDateTime vencimento = LocalDateTime.of(2026, 11, 3, 18, 0);
        Assinatura assinatura = Assinatura.builder().restaurante(loja).status(Assinatura.Status.ATIVA)
                .plano(Plano.MENSAL).valor(new BigDecimal("99.90")).validaAte(vencimento)
                .proximaCobranca(vencimento).build();
        when(assinaturas.findLockedByRestauranteId(48L)).thenReturn(Optional.of(assinatura));
        when(catalogo.valorAtual(Plano.MENSAL)).thenReturn(new BigDecimal("75.00"));

        var resposta = service.mudarParaEssencial(loja);

        assertThat(loja.getFiscalHabilitado()).isFalse();
        assertThat(loja.getValorMensalPersonalizado()).isNull();
        assertThat(assinatura.getValor()).isEqualByComparingTo("75.00");
        assertThat(assinatura.getValidaAte()).isEqualTo(vencimento);
        assertThat(assinatura.getProximaCobranca()).isEqualTo(vencimento);
        assertThat(resposta.get("valorProximaCobranca")).isEqualTo(new BigDecimal("75.00"));
        verify(restaurantes).save(loja);
        verify(assinaturas).save(assinatura);
    }

    @Test
    void naoRemoveFiscalDeOutroTipoDePlano() {
        Restaurante loja = new Restaurante();
        loja.setId(48L);
        loja.setFiscalHabilitado(true);
        Assinatura assinatura = Assinatura.builder().restaurante(loja).status(Assinatura.Status.ATIVA)
                .plano(Plano.ANUAL).valor(new BigDecimal("550.00")).build();
        when(assinaturas.findLockedByRestauranteId(48L)).thenReturn(Optional.of(assinatura));

        assertThatThrownBy(() -> service.mudarParaEssencial(loja)).isInstanceOf(IllegalArgumentException.class);
        verify(restaurantes, never()).save(loja);
        verify(assinaturas, never()).save(assinatura);
    }
}
