package com.mydelivery.service;

import com.mydelivery.model.*;
import com.mydelivery.repository.ZonaEntregaRepository;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntregaRaioTest {
    ZonaEntregaRepository repo = mock(ZonaEntregaRepository.class);
    EntregaService service = new EntregaService(repo);
    Restaurante loja() {
        Restaurante r = new Restaurante(); r.setId(61L);
        r.setEnderecoLatitude(BigDecimal.valueOf(-25.3159));
        r.setEnderecoLongitude(BigDecimal.valueOf(-49.2366)); return r;
    }
    ZonaEntrega zona(String raio, String taxa) {
        return ZonaEntrega.builder().raioKm(new BigDecimal(raio)).taxa(new BigDecimal(taxa)).build();
    }
    @Test void aplicaMenorFaixaEmKmENaoEmDiametro() {
        var r = loja();
        when(repo.findByRestauranteIdOrderByRaioKmAsc(61L)).thenReturn(List.of(zona("1","6"),zona("2","7.50"),zona("5","10")));
        double lat = r.getEnderecoLatitude().doubleValue(), lng = r.getEnderecoLongitude().doubleValue();
        assertThat(service.calcularTaxaPorRaio(r,lat,lng)).isEqualByComparingTo("6");
        assertThat(service.calcularTaxaPorRaio(r,lat+Math.toDegrees(1.5/6371),lng)).isEqualByComparingTo("7.50");
        assertThat(service.calcularTaxaPorRaio(r,lat+Math.toDegrees(5.0/6371),lng)).isEqualByComparingTo("10");
        assertThat(service.calcularTaxaPorRaio(r,lat+Math.toDegrees(4.999/6371),lng)).isEqualByComparingTo("10");
        assertThat(service.calcularTaxaPorRaio(r,lat+Math.toDegrees(5.001/6371),lng)).isNull();
    }
    @Test void geocoderRejeitaCentroDeCidadeEBairroEAceitaRuaComPrecisaoExplicita() {
        var address = Map.of("city","Colombo","suburb","Arruda","ISO3166-2-lvl4","BR-PR");
        assertThat(EntregaService.lerLocalizacao(Map.of("lat","-25.29","lon","-49.22","place_rank",16,"address",address))).isNull();
        assertThat(EntregaService.lerLocalizacao(Map.of("lat","-25.29","lon","-49.22","place_rank",22,"address",address))).isNull();
        var rua=EntregaService.lerLocalizacao(Map.of("lat","-25.3159","lon","-49.2366","place_rank",26,"address",address));
        assertThat(rua.precisao()).isEqualTo("rua");
        assertThat(rua.bairros()).contains("Arruda");
    }
    @Test void zonasInvalidasNaoExcluemConfiguracaoAnterior() {
        for (var zonas : List.of(
                List.of(Map.<String,Object>of("raioKm",5,"taxa",-1)),
                List.of(Map.<String,Object>of("raioKm",5,"taxa",1),Map.<String,Object>of("raioKm",5.0,"taxa",2)),
                List.of(Map.<String,Object>of("raioKm",0.001,"taxa",1)))) {
            assertThatThrownBy(() -> service.substituirZonas(loja(),zonas)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repo);
    }
    @Test void aceitaTaxaZeroEListaImutavelOrdenandoFaixas() {
        var zonas=service.substituirZonas(loja(),List.of(Map.of("raioKm",5,"taxa",10),Map.of("raioKm",1,"taxa",0)));
        assertThat(zonas.getFirst().getRaioKm()).isEqualByComparingTo("1");
        assertThat(zonas.getFirst().getTaxa()).isZero();
        assertThat(zonas.getLast().getOrdem()).isEqualTo(1);
    }
    @Test void ruaInteiraNaMesmaTaxaPodeSerCalculadaSemInteracaoDoCliente() {
        var r=loja();
        when(repo.findByRestauranteIdOrderByRaioKmAsc(61L)).thenReturn(List.of(zona("1","6"),zona("2","7.50"),zona("5","10")));
        var dentro=new EntregaService.Localizacao(-25.3159,-49.2366,"rua",List.of("Arruda"),"Colombo","BR-PR","",0.2);
        var cruza=new EntregaService.Localizacao(-25.3159+Math.toDegrees(1.0/6371),-49.2366,"rua",List.of("Arruda"),"Colombo","BR-PR","",0.2);
        assertThat(service.taxaUnicaNaRua(r,dentro)).isTrue();
        assertThat(service.taxaUnicaNaRua(r,cruza)).isFalse();
    }
    @Test void faixaIntermediariaDiferenteNaoEPulada() {
        when(repo.findByRestauranteIdOrderByRaioKmAsc(61L)).thenReturn(List.of(zona("1","6"),zona("2","10"),zona("5","6")));
        var rua=new EntregaService.Localizacao(-25.3159+Math.toDegrees(1.5/6371),-49.2366,"rua",List.of("Arruda"),"Colombo","BR-PR","",1.0);
        assertThat(service.taxaUnicaNaRua(loja(),rua)).isFalse();
    }
    @Test void naoAceitaCoordenadasNaoFinitasOuForaDoPlaneta() {
        assertThatThrownBy(() -> service.calcularTaxaPorRaio(loja(),Double.NaN,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.calcularTaxaPorRaio(loja(),-25,181)).isInstanceOf(IllegalArgumentException.class);
    }
}
