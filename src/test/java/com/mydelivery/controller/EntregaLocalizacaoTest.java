package com.mydelivery.controller;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.EntregaService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class EntregaLocalizacaoTest {
    @Test void cardapioAntigoRecebeAMesmaTaxaPorBairroMesmoComRuaInexistente() {
        var service=mock(EntregaService.class);var repo=mock(RestauranteRepository.class);
        var r=new Restaurante();when(repo.findBySlug("loja")).thenReturn(Optional.of(r));
        when(service.consultarTaxaBairro(r,"sao jao")).thenReturn(new EntregaService.CotacaoBairro("São João",BigDecimal.TEN,-25.3,-49.2));
        var result=new EntregaController(service,repo).localizarEndereco("loja","Rua inexistente","999","sao jao","","","");
        assertThat(result.getBody()).containsEntry("taxa",BigDecimal.TEN).containsEntry("bairro","São João").containsEntry("localizado",true);
        verify(service,never()).localizar(anyString(),any(),any());
    }
}
