package com.mydelivery.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GoogleGeocodingServiceTest {
    @Test
    void parsesBrazilianStreetAddressComponents() {
        Map<String, Object> result = Map.of(
                "location", Map.of("latitude", -20.14, "longitude", -40.25),
                "addressComponents", List.of(
                        component("Rua Rio Jucu", "R. Rio Jucu", "route"),
                        component("220", "220", "street_number"),
                        component("Eldorado", "Eldorado", "sublocality", "sublocality_level_1"),
                        component("Serra", "Serra", "administrative_area_level_2"),
                        component("Espírito Santo", "ES", "administrative_area_level_1"),
                        component("29169-220", "29169-220", "postal_code")));

        var location = GoogleGeocodingService.lerLocalizacao(result);

        assertThat(location).isNotNull();
        assertThat(location.rua()).isEqualTo("Rua Rio Jucu");
        assertThat(location.numero()).isEqualTo("220");
        assertThat(location.bairro()).isEqualTo("Eldorado");
        assertThat(location.cidade()).isEqualTo("Serra");
        assertThat(location.uf()).isEqualTo("ES");
        assertThat(location.cep()).isEqualTo("29169-220");
    }

    @Test
    void rejectsCoordinatesWithoutAStreet() {
        assertThat(GoogleGeocodingService.lerLocalizacao(Map.of(
                "location", Map.of("latitude", -20.14, "longitude", -40.25),
                "addressComponents", List.of(component("Serra", "Serra", "locality"))))).isNull();
    }

    private static Map<String, Object> component(String longText, String shortText, String... types) {
        return Map.of("longText", longText, "shortText", shortText, "types", List.of(types));
    }
}
