package com.mydelivery.beneficios;
import com.mydelivery.model.*;
import com.mydelivery.repository.*;
import com.mydelivery.service.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssinaturaBeneficioTest {
 @Test void grantedMonthsSurviveStatusCorrectionAndPostponeBilling(){
  var repo=mock(AssinaturaRepository.class);var restaurants=mock(RestauranteRepository.class);var catalog=mock(PlanoCatalogoService.class);var email=mock(EmailService.class);
  var service=new AssinaturaService(repo,restaurants,catalog,mock(com.mydelivery.service.meta.MetaCapiService.class),mock(PagamentoMensalidadeRepository.class),email);
  var r=Restaurante.builder().id(1L).status(Restaurante.Status.ATIVO).nome("Loja").build();
  var paidUntil=LocalDateTime.now().plusDays(20);
  var a=Assinatura.builder().restaurante(r).status(Assinatura.Status.ATIVA).plano(Plano.MENSAL).validaAte(paidUntil).proximaCobranca(paidUntil).ultimaCobranca(LocalDateTime.now().minusDays(10)).build();
  when(repo.findLockedByRestauranteId(1L)).thenReturn(Optional.of(a));when(repo.save(any())).thenAnswer(i->i.getArgument(0));
  service.concederMesesGratis(r,3,"myMoney meta 500");
  assertEquals(paidUntil.plusMonths(3),a.getValidaAte());assertEquals(paidUntil.plusMonths(3),a.getProximaCobranca());
  var status=service.obterStatus(r);assertEquals(paidUntil.plusMonths(3),a.getValidaAte());assertTrue(((Number)status.get("diasRestantes")).intValue()>90);
 }
 @Test void ordinaryInflatedLegacyValidityStillGetsCorrected(){
  var repo=mock(AssinaturaRepository.class);var service=new AssinaturaService(repo,mock(RestauranteRepository.class),mock(PlanoCatalogoService.class),mock(com.mydelivery.service.meta.MetaCapiService.class),mock(PagamentoMensalidadeRepository.class),mock(EmailService.class));
  var r=Restaurante.builder().id(1L).status(Restaurante.Status.ATIVO).build();var a=Assinatura.builder().restaurante(r).status(Assinatura.Status.ATIVA).plano(Plano.MENSAL).validaAte(LocalDateTime.now().plusYears(1)).build();
  when(repo.findLockedByRestauranteId(1L)).thenReturn(Optional.of(a));service.obterStatus(r);assertTrue(a.getValidaAte().isBefore(LocalDateTime.now().plusMonths(2)));
 }
}
