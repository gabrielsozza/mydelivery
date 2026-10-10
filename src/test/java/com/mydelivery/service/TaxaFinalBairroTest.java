package com.mydelivery.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mydelivery.model.*;
import com.mydelivery.repository.ZonaEntregaRepository;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
class TaxaFinalBairroTest {
    EntregaService.Localizacao bairro(String nome,double lat) {
        return new EntregaService.Localizacao(lat,-49.2366,"bairro",List.of(nome),"Colombo","PR","",0,"","");
    }
    @Test void faixaDoBairroECotadaPeloServidorSemPrecisarDeImovel() {
        var repo=mock(ZonaEntregaRepository.class);var service=spy(new EntregaService(repo));
        var r=new Restaurante();r.setId(61L);r.setModoTaxa("RAIO");r.setCidade("Colombo");r.setEstado("PR");
        r.setEnderecoLatitude(new BigDecimal("-25.3159"));r.setEnderecoLongitude(new BigDecimal("-49.2366"));
        when(repo.findByRestauranteIdOrderByRaioKmAsc(61L)).thenReturn(List.of(ZonaEntrega.builder().raioKm(BigDecimal.ONE).taxa(new BigDecimal("6")).build(),ZonaEntrega.builder().raioKm(BigDecimal.TEN).taxa(BigDecimal.TEN).build()));
        doReturn(List.of(bairro("Arruda",-25.316))).when(service).localizarBairro("Arruda","Colombo","PR");
        var cotacao=service.consultarTaxaBairro(r,"Arruda");assertThat(cotacao.taxa()).isEqualByComparingTo("6");
        // Faixas alteradas pela loja são respeitadas mesmo com bairro em cache.
        when(repo.findByRestauranteIdOrderByRaioKmAsc(61L)).thenReturn(List.of(ZonaEntrega.builder().raioKm(BigDecimal.ONE).taxa(BigDecimal.ZERO).build()));
        assertThat(service.consultarTaxaBairro(r,"Arruda").taxa()).isZero();
    }
    @Test void corrigeNomeSemListaENaoEscolheBairroAmbiguo() {
        assertThat(EntregaService.selecionarBairro(List.of(bairro("São João",-25.3),bairro("São José",-25.4)),"sao jao").getFirst().bairros()).containsExactly("São João");
        assertThat(EntregaService.selecionarBairro(List.of(bairro("Arruda",-25.3),bairro("Arruda",-25.4)),"Arruda")).isEmpty();
    }
    Map<String,Object> feature(String city,String state,String kind) {
        return Map.of("properties",Map.of("countrycode","BR","type","district","osm_value",kind,"city",city,"state",state,"name","Arruda"),"geometry",Map.of("coordinates",List.of(-49.2366,-25.3)));
    }
    @Test void bairroDeOutraCidadeOuEstadoNaoViraTaxaLocal() {
        assertThat(EntregaService.lerBairroPhoton(feature("Colombo","Paraná","suburb"),"Colombo","PR")).isNotNull();
        assertThat(EntregaService.lerBairroPhoton(feature("Colombo","Paraná","suburb"),"Rio de Janeiro","RJ")).isNull();
        assertThat(EntregaService.lerBairroPhoton(feature("Colombo","Paraná","city"),"Colombo","PR")).isNull();
    }
    @Test void regiaoPedregosoEValidaSemConfundirComMorroDeMesmoNome() {
        var endereco=Map.of("quarter","Pedregoso","suburb","Campo Grande","city","Rio de Janeiro","ISO3166-2-lvl4","BR-RJ");
        var bairro=Map.<String,Object>of("lat","-22.8705703","lon","-43.5631514","name","Pedregoso","addresstype","quarter","address",endereco);
        assertThat(EntregaService.lerBairroNominatim(bairro,"Rio de Janeiro","RJ").bairros()).containsExactly("Pedregoso");
        var morro=new HashMap<>(bairro);morro.put("addresstype","peak");
        assertThat(EntregaService.lerBairroNominatim(morro,"Rio de Janeiro","RJ")).isNull();
        assertThat(EntregaService.lerBairroNominatim(bairro,"Colombo","PR")).isNull();
    }
}
