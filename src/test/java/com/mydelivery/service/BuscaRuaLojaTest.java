package com.mydelivery.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class BuscaRuaLojaTest {
    Map<String,Object> feature(String name,String bairro,String cidade,String estado,String tipo) {
        return Map.of("properties",Map.of("countrycode","BR","type",tipo,"osm_key","highway","name",name,"district",bairro,"city",cidade,"state",estado,"postcode","23087-005"),
                "geometry",Map.of("coordinates",List.of(-43.5649233,-22.8815123)));
    }
    @Test void divergenciaRealDoMapaRetornaSomenteRuaParaConferencia() {
        var f=feature("Rua Maestro Joaquim Naegele","Campo Grande","Rio de Janeiro","Rio de Janeiro","street");
        var l=EntregaService.lerRuaPhoton(f,"R. Maestro Joaquim Maegele","Campo Grande","Rio de Janeiro","RJ");
        assertThat(l).isNotNull();assertThat(l.precisao()).isEqualTo("rua");assertThat(l.numero()).isEmpty();
        assertThat(l.lat()).isEqualTo(-22.8815123);assertThat(l.incertezaKm()).isInfinite();
    }
    @Test void jamaisUsaCentroDeCidadeBairroOuEnderecoDeOutraRegiao() {
        for(var f:List.of(feature("Rua Maestro Joaquim Naegele","Campo Grande","Rio de Janeiro","Rio de Janeiro","city"),
                feature("Rua Maestro Joaquim Naegele","Centro","Rio de Janeiro","Rio de Janeiro","street"),
                feature("Rua Maestro Joaquim Naegele","Campo Grande","Outra cidade","Rio de Janeiro","street"),
                feature("Rua Maestro Joaquim Naegele","Campo Grande","Rio de Janeiro","Espírito Santo","street"))) {
            assertThat(EntregaService.lerRuaPhoton(f,"Rua Maestro Joaquim Maegele","Campo Grande","Rio de Janeiro","RJ")).isNull();
        }
    }
    @Test void variacaoDeGrafiaNaoAceitaOutraRuaOuNumero() {
        assertThat(EntregaService.mesmaRuaAproximada("Rua Maestro Joaquim Naegele","Rua Maestro Joaquim Maegele")).isTrue();
        assertThat(EntregaService.mesmaRuaAproximada("Rua 1","Rua 2")).isFalse();
        assertThat(EntregaService.mesmaRuaAproximada("Rua Rio Jucu","Rua Jucu")).isFalse();
        assertThat(EntregaService.mesmaRuaAproximada("Rua Joaquim Ribeiro","Rua Maestro Joaquim Maegele")).isFalse();
    }
}
