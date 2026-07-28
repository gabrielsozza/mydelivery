package com.mydelivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.mydelivery.model.Assinatura;
import com.mydelivery.model.Restaurante;
import com.mydelivery.model.Usuario;
import com.mydelivery.repository.AssinaturaRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.repository.UsuarioRepository;

/**
 * Cobre o fix do incidente Monkeys #9: desbloquear precisa empurrar
 * trialExpiraEm (Restaurante), trialFim + proximaCobranca (Assinatura)
 * pra now+dias. Sem isso, loja continuava bloqueada mesmo status=ATIVO.
 */
class AdminServiceDesbloqueioTest {

    private AdminService construir(RestauranteRepository restRepo, AssinaturaRepository assRepo) {
        PedidoRepository pedRepo = mock(PedidoRepository.class);
        UsuarioRepository usuRepo = mock(UsuarioRepository.class);
        return new AdminService(restRepo, assRepo, pedRepo, usuRepo);
    }

    @Test
    void desbloquear_com_dias_customizados_empurra_todas_datas_de_trial() {
        RestauranteRepository restRepo = mock(RestauranteRepository.class);
        AssinaturaRepository assRepo = mock(AssinaturaRepository.class);

        // Restaurante bloqueado com trialExpiraEm no passado (situação Monkeys #9)
        Restaurante r = new Restaurante();
        r.setId(9L);
        r.setStatus(Restaurante.Status.BLOQUEADO);
        r.setBloqueadoEm(LocalDateTime.now().minusDays(2));
        r.setMotivoBloqueio("Trial expirado");
        r.setTrialExpiraEm(LocalDateTime.now().minusDays(2));
        Usuario u = new Usuario();
        u.setEmail("monkeys@teste.com");
        r.setUsuario(u);

        Assinatura a = new Assinatura();
        a.setId(50L);
        a.setRestaurante(r);
        a.setStatus(Assinatura.Status.INADIMPLENTE);
        a.setValor(new BigDecimal("99.90"));
        a.setTrialFim(LocalDateTime.now().minusDays(2));
        a.setProximaCobranca(LocalDateTime.now().minusDays(2));

        when(restRepo.findById(9L)).thenReturn(Optional.of(r));
        when(restRepo.save(any(Restaurante.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assRepo.findByRestauranteId(9L)).thenReturn(Optional.of(a));
        when(assRepo.save(any(Assinatura.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminService svc = construir(restRepo, assRepo);

        // Act — desbloqueia com 15 dias
        LocalDateTime antes = LocalDateTime.now();
        svc.desbloquearRestaurante(9L, 15);
        LocalDateTime depois = LocalDateTime.now();

        // Restaurante: status ATIVO + trialExpiraEm empurrado pro futuro
        ArgumentCaptor<Restaurante> restCap = ArgumentCaptor.forClass(Restaurante.class);
        verify(restRepo).save(restCap.capture());
        Restaurante salvo = restCap.getValue();
        assertThat(salvo.getStatus()).isEqualTo(Restaurante.Status.ATIVO);
        assertThat(salvo.getBloqueadoEm()).isNull();
        assertThat(salvo.getMotivoBloqueio()).isNull();
        assertThat(salvo.getTrialExpiraEm()).isAfter(antes.plusDays(14).plusHours(23));
        assertThat(salvo.getTrialExpiraEm()).isBefore(depois.plusDays(15).plusMinutes(1));

        // Assinatura: status ATIVA + proximaCobranca/trialFim empurradas
        ArgumentCaptor<Assinatura> assCap = ArgumentCaptor.forClass(Assinatura.class);
        verify(assRepo).save(assCap.capture());
        Assinatura assSalva = assCap.getValue();
        assertThat(assSalva.getStatus()).isEqualTo(Assinatura.Status.ATIVA);
        assertThat(assSalva.getProximaCobranca()).isAfter(antes.plusDays(14).plusHours(23));
        assertThat(assSalva.getTrialFim()).isAfter(antes.plusDays(14).plusHours(23));
    }

    @Test
    void desbloquear_sem_dias_usa_default_30() {
        RestauranteRepository restRepo = mock(RestauranteRepository.class);
        AssinaturaRepository assRepo = mock(AssinaturaRepository.class);

        Restaurante r = new Restaurante();
        r.setId(1L);
        r.setStatus(Restaurante.Status.BLOQUEADO);
        Usuario u = new Usuario();
        u.setEmail("teste@x.com");
        r.setUsuario(u);
        when(restRepo.findById(1L)).thenReturn(Optional.of(r));
        when(restRepo.save(any(Restaurante.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assRepo.findByRestauranteId(1L)).thenReturn(Optional.empty());

        AdminService svc = construir(restRepo, assRepo);

        LocalDateTime antes = LocalDateTime.now();
        svc.desbloquearRestaurante(1L); // overload sem dias → default 30
        LocalDateTime depois = LocalDateTime.now();

        ArgumentCaptor<Restaurante> cap = ArgumentCaptor.forClass(Restaurante.class);
        verify(restRepo).save(cap.capture());
        assertThat(cap.getValue().getTrialExpiraEm()).isAfter(antes.plusDays(29).plusHours(23));
        assertThat(cap.getValue().getTrialExpiraEm()).isBefore(depois.plusDays(30).plusMinutes(1));
    }
}
