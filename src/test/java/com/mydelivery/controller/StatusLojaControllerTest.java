package com.mydelivery.controller;
import com.mydelivery.model.*;
import com.mydelivery.repository.*;
import com.mydelivery.service.*;
import com.mydelivery.service.mercadopago.MercadoPagoClient;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class StatusLojaControllerTest {
    @Test void abreManualESalvaOverrideSemAlterarOutrasConfiguracoes(){
        var repo=mock(RestauranteRepository.class);var horario=mock(HorarioLojaService.class);
        var loja=new Restaurante();loja.setAberto(false);loja.setAceitarPedidosAutomaticamente(true);
        when(repo.findByUsuarioEmail("dono@test")).thenReturn(Optional.of(loja));
        var limite=LocalDateTime.now().plusHours(3);when(horario.proximoLimite(loja,true)).thenReturn(limite);
        var c=new ConfiguracaoController(mock(ConfiguracaoService.class),repo,mock(ConfiguracaoRestauranteRepository.class),mock(MercadoPagoClient.class),horario);
        assertThat(c.atualizarStatusLoja("dono@test",Map.of("aberto",true)).getBody()).containsEntry("aberto",true);
        assertThat(loja.getStatusManualAte()).isEqualTo(limite);assertThat(loja.getAceitarPedidosAutomaticamente()).isTrue();verify(repo).save(loja);
    }
}
