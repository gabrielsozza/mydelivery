package com.mydelivery.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GoogleGeocodingServiceTest {

    @Test
    void converteEnderecoBrasileiroCompletoSemConfundirBairroComCidade() {
        Map<String,Object> resultado = Map.of(
                "location", Map.of("latitude", -20.144809, "longitude", -40.2590467),
                "granularity", "ROOFTOP",
                "viewport", Map.of(
                        "low", Map.of("latitude", -20.146, "longitude", -40.260),
                        "high", Map.of("latitude", -20.143, "longitude", -40.258)),
                "types", List.of("street_address"),
                "addressComponents", List.of(
                        componente("Rua Rio Jucú", "R. Rio Jucú", "route"),
                        componente("220", "220", "street_number"),
                        componente("Eldorado", "Eldorado", "sublocality_level_1", "sublocality", "political"),
                        componente("Serra", "Serra", "administrative_area_level_2", "political"),
                        componente("Espírito Santo", "ES", "administrative_area_level_1", "political"),
                        componente("29169-220", "29169-220", "postal_code")));

        var local = GoogleGeocodingService.lerLocalizacao(resultado);

        assertThat(local).isNotNull();
        assertThat(local.precisao()).isEqualTo("imovel");
        assertThat(local.rua()).isEqualTo("Rua Rio Jucú");
        assertThat(local.numero()).isEqualTo("220");
        assertThat(local.bairros()).containsExactly("Eldorado");
        assertThat(local.cidade()).isEqualTo("Serra");
        assertThat(local.uf()).isEqualTo("ES");
        assertThat(local.cep()).isEqualTo("29169-220");
    }

    @Test
    void rejeitaResultadoSemVia() {
        var resultado = Map.<String,Object>of(
                "location", Map.of("latitude", -20.1, "longitude", -40.2),
                "granularity", "APPROXIMATE",
                "addressComponents", List.of(componente("Serra", "Serra", "locality")));
        assertThat(GoogleGeocodingService.lerLocalizacao(resultado)).isNull();
    }

    private static Map<String,Object> componente(String longo, String curto, String... tipos) {
        return Map.of("longText", longo, "shortText", curto, "types", List.of(tipos));
    }
}
