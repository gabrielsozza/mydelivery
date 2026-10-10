package com.mydelivery.service;

import com.mydelivery.model.*;
import com.mydelivery.repository.ZonaEntregaRepository;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntregaTrajetoTest {
    ZonaEntregaRepository repo = mock(ZonaEntregaRepository.class);
    RotaEntregaService rotas = mock(RotaEntregaService.class);
    EntregaService service = new EntregaService(repo);
    Restaurante loja = new Restaurante();
    EntregaTrajetoTest() {
        loja.setId(61L); loja.setModoTaxa("TRAJETO");
        loja.setEnderecoLatitude(new BigDecimal("-25.3159081"));
        loja.setEnderecoLongitude(new BigDecimal("-49.2366051"));
        ReflectionTestUtils.setField(service,"rotaEntregaService",rotas);
        when(repo.findByRestauranteIdOrderByRaioKmAsc(61L)).thenReturn(List.of(
            ZonaEntrega.builder().raioKm(BigDecimal.ONE).taxa(new BigDecimal("6")).build(),
            ZonaEntrega.builder().raioKm(new BigDecimal("5")).taxa(BigDecimal.TEN).build()));
    }
    @Test void centroDeColomboDentroDoCirculoMasForaDoPercursoFicaBloqueado() {
        assertThat(EntregaService.distanciaHaversineKm(-25.3159081,-49.2366051,-25.29167,-49.22417)).isLessThan(5);
        when(rotas.distanciaKm(-25.3159081,-49.2366051,-25.29167,-49.22417)).thenReturn(5.1233);
        assertThat(service.calcularTaxaPorRaio(loja,-25.29167,-49.22417)).isNull();
    }
    @Test void limiteExatoSemArredondarParaLiberarEnderecosAcimaDeCincoKm() {
        when(rotas.distanciaKm(anyDouble(),anyDouble(),anyDouble(),anyDouble())).thenReturn(4.999,5.0,5.0001,0.0);
        assertThat(service.calcularTaxaPorRaio(loja,-25.3,-49.23)).isEqualByComparingTo("10");
        assertThat(service.calcularTaxaPorRaio(loja,-25.3,-49.23)).isEqualByComparingTo("10");
        assertThat(service.calcularTaxaPorRaio(loja,-25.3,-49.23)).isNull();
        assertThat(service.calcularTaxaPorRaio(loja,-25.3,-49.23)).isEqualByComparingTo("6");
    }
    @Test void falhaDeRoteamentoNaoUsaDistanciaEmLinhaReta() {
        when(rotas.distanciaKm(anyDouble(),anyDouble(),anyDouble(),anyDouble())).thenThrow(new IllegalArgumentException("indisponível"));
        assertThatThrownBy(()->service.calcularTaxaPorRaio(loja,-25.3,-49.23)).hasMessageContaining("indisponível");
    }
    @Test void outrasLojasEmRaioPreservamRegrasENaoConsultamRotas() {
        loja.setModoTaxa("RAIO");
        assertThat(service.calcularTaxaPorRaio(loja,-25.29167,-49.22417)).isEqualByComparingTo("10");
        verifyNoInteractions(rotas);
    }
    @Test void centroDaRuaNaoServeComoGarantiaDoTrajetoAteOImovel() {
        assertThat(service.taxaUnicaNaRua(loja,new EntregaService.Localizacao(-25.31,-49.23,"rua",List.of("Arruda"),"Colombo","BR-PR","",0.1))).isFalse();
    }
    @Test void distanciaEmMetrosIncluiAcessosSemConfundirUnidades() {
        assertThat(RotaEntregaService.lerDistanciaKm(Map.of("code","Ok","routes",List.of(Map.of("distance",4990)),"waypoints",List.of(Map.of("distance",5),Map.of("distance",6))))).isEqualTo(5.001);
    }
    @Test void semRotaOuViaDistanteOuDadosInvalidosNaoLibera() {
        for (var body:List.of(Map.of("code","NoRoute"),Map.of("code","Ok","routes",List.of(Map.of("distance",1000)),"waypoints",List.of(Map.of("distance",76),Map.of("distance",0))),Map.of("code","Ok","routes",List.of(Map.of("distance",Double.NaN)),"waypoints",List.of(Map.of("distance",0),Map.of("distance",0)))))
            assertThatThrownBy(()->RotaEntregaService.lerDistanciaKm(body)).isInstanceOf(IllegalArgumentException.class);
    }
}
