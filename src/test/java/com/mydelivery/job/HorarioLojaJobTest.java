package com.mydelivery.job;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.HorarioLojaService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HorarioLojaJobTest {
    @Test void manuallyOpenedStoreRemainsOpenUntilScheduleBoundary() {
        var repo = mock(RestauranteRepository.class);
        var hours = mock(HorarioLojaService.class);
        var zone = ZoneId.of("America/Sao_Paulo");
        var restaurant = Restaurante.builder().id(1L).aberto(true)
                .statusManualAte(LocalDateTime.now(zone).plusHours(1)).build();
        when(repo.findAll()).thenReturn(List.of(restaurant));
        when(hours.getZone()).thenReturn(zone);
        new HorarioLojaJob(repo, hours).atualizarStatusAutomatico();
        assertTrue(restaurant.getAberto());
        verify(repo, never()).save(any());

        restaurant.setStatusManualAte(LocalDateTime.now(zone).minusMinutes(1));
        var outside = new HorarioLojaService.EstadoHorario();
        outside.horarioConfigurado = true;
        when(hours.calcular(restaurant)).thenReturn(outside);
        new HorarioLojaJob(repo, hours).atualizarStatusAutomatico();
        assertFalse(restaurant.getAberto());
        verify(repo).save(restaurant);
    }
}
