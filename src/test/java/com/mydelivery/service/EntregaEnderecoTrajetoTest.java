package com.mydelivery.service;
import com.mydelivery.model.*;
import com.mydelivery.repository.ZonaEntregaRepository;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class EntregaEnderecoTrajetoTest {
    EntregaService service=spy(new EntregaService(mock(ZonaEntregaRepository.class)));
    Restaurante r=new Restaurante();
    EntregaEnderecoTrajetoTest(){r.setModoTaxa("TRAJETO");r.setCidade("Colombo");r.setEstado("PR");r.setEnderecoLatitude(BigDecimal.valueOf(-25.3));r.setEnderecoLongitude(BigDecimal.valueOf(-49.2));}
    EntregaService.Localizacao local(String precisao,String numero){return new EntregaService.Localizacao(-25.31,-49.21,precisao,List.of("Centro"),"Colombo","PR",numero,0,"Rua A","");}
    @Test void numeroExatoUsaTaxaDaRota(){
        doReturn(List.of(local("imovel","10"))).when(service).localizar(anyString(),any(),any());
        doReturn(BigDecimal.TEN).when(service).calcularTaxaPorRaio(r,-25.31,-49.21);
        assertThat(service.consultarTaxaEndereco(r,"Rua A","10","Centro","","","").taxa()).isEqualByComparingTo("10");
        verify(service).localizar("Rua A, 10, Centro, Colombo, PR",-25.3,-49.2);
    }
    @Test void ruaSemNumeroEImovelErradoNaoLiberamPedido(){
        for(var local:List.of(local("rua",""),local("imovel","20"))){
            doReturn(List.of(local)).when(service).localizar(anyString(),any(),any());
            assertThatThrownBy(()->service.consultarTaxaEndereco(r,"Rua A","10","Centro","","","")).isInstanceOf(IllegalArgumentException.class);
        }
        verify(service,never()).calcularTaxaPorRaio(any(),anyDouble(),anyDouble());
    }
    @Test void foraDaCoberturaPermaneceSemTaxa(){
        doReturn(List.of(local("imovel","10"))).when(service).localizar(anyString(),any(),any());
        doReturn(null).when(service).calcularTaxaPorRaio(r,-25.31,-49.21);
        assertThat(service.consultarTaxaEndereco(r,"Rua A","10","Centro","","","").taxa()).isNull();
    }
}
