package com.mydelivery.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.mydelivery.model.*;
class PedidoEntregaTaxaTest {
    EntregaService entrega=mock(EntregaService.class);
    PedidoService service;
    PedidoEntregaTaxaTest() {
        service=mock(PedidoService.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service,"entregaService",entrega);
    }
    Restaurante loja(String modo) {
        var r=new Restaurante();r.setModoTaxa(modo);r.setBairrosAtendidos(List.of(new BairroEntrega("São João",BigDecimal.TEN)));return r;
    }
    @Test void bairroDefineTaxaFinalSemRuaNumeroOuCoordenadas() {
        for(String modo:List.of("RAIO")) {
            var r=loja(modo);
            when(entrega.consultarTaxaBairro(r,"sao jao")).thenReturn(new EntregaService.CotacaoBairro("São João",new BigDecimal("7.50"),-25.3,-49.2));
            assertThat(service.calcularTaxaEntrega(r,Map.of("bairro","sao jao"))).isEqualByComparingTo("7.50");
            assertThat(service.calcularTaxaEntrega(r,Map.of("bairro","sao jao","rua","Outra rua","numero","999","latitude","0","longitude","0"))).isEqualByComparingTo("7.50");
            verify(entrega,never()).calcularTaxaPorRaio(eq(r),anyDouble(),anyDouble());
        }
    }
    @Test void naoConfiaEmTaxaEnviadaPeloCliente() {
        var r=loja("RAIO");when(entrega.consultarTaxaBairro(r,"São João")).thenReturn(new EntregaService.CotacaoBairro("São João",BigDecimal.TEN,-25.3,-49.2));
        assertThat(service.calcularTaxaEntrega(r,Map.of("bairro","São João","taxa","0"))).isEqualByComparingTo("10");
    }
    @Test void bairroForaNaoViraFreteGratis() {
        var r=loja("RAIO");when(entrega.consultarTaxaBairro(r,"Desconhecido")).thenReturn(new EntregaService.CotacaoBairro("Desconhecido",null,null,null));
        assertThatThrownBy(()->service.calcularTaxaEntrega(r,Map.of("bairro","Desconhecido"))).hasMessageContaining("não entrega nesse bairro");
    }
    @Test void taxaZeroConfiguradaEValida() {
        var r=loja("RAIO");when(entrega.consultarTaxaBairro(r,"São João")).thenReturn(new EntregaService.CotacaoBairro("São João",BigDecimal.ZERO,-25.3,-49.2));
        assertThat(service.calcularTaxaEntrega(r,Map.of("bairro","São João"))).isZero();
    }
    @Test void modoBairroPreservaTabelaCadastradaSemConsultarMapa() {
        assertThat(service.calcularTaxaEntrega(loja("BAIRRO"),Map.of("bairro","sao jao"))).isEqualByComparingTo("10");verifyNoInteractions(entrega);
    }
    @Test void trajetoUsaEnderecoCompletoEIgnoraCoordenadasETaxaDoCliente() {
        var r=loja("TRAJETO");
        when(entrega.consultarTaxaEndereco(r,"Rua A","10","Centro","Colombo","PR","83400000"))
            .thenReturn(new EntregaService.CotacaoBairro("Centro",new BigDecimal("8"),-25.3,-49.2));
        assertThat(service.calcularTaxaEntrega(r,Map.of("rua","Rua A","numero","10","bairro","Centro",
            "cidade","Colombo","estado","PR","cep","83400000","latitude","0","longitude","0","taxa","0"))).isEqualByComparingTo("8");
        verify(entrega,never()).consultarTaxaBairro(any(),any());
    }

}
