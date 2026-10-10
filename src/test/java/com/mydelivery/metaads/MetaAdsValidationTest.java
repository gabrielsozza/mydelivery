package com.mydelivery.metaads;

import static org.junit.jupiter.api.Assertions.*;
import com.mydelivery.model.Restaurante;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MetaAdsValidationTest {
    private final Restaurante restaurant = new Restaurante();

    @Test void refusesBroadTargetingWithoutStoreCoordinates() {
        var draft = new MetaAdsService.Draft("Jantar", "Peça agora", "https://example.com/foto.jpg",
                new BigDecimal("20.00"), 7, 10);
        assertThrows(ResponseStatusException.class, () -> MetaAdsService.validateDraft(restaurant, draft));
    }

    @Test void rejectsInvalidBudgetAndImage() {
        restaurant.setEnderecoLatitude(new BigDecimal("-22.90"));
        restaurant.setEnderecoLongitude(new BigDecimal("-43.20"));
        var draft = new MetaAdsService.Draft("Jantar", "Peça agora", "http://example.com/foto.jpg",
                new BigDecimal("1.00"), 7, 10);
        assertThrows(ResponseStatusException.class, () -> MetaAdsService.validateDraft(restaurant, draft));
    }
    @Test void acceptsCustomRadiusBetweenEndpoints() {
        restaurant.setEnderecoLatitude(new BigDecimal("-22.90"));
        restaurant.setEnderecoLongitude(new BigDecimal("-43.20"));
        var draft = new MetaAdsService.Draft("Jantar", "Peça agora", "https://example.com/foto.jpg",
                new BigDecimal("20.00"), 7, 13);
        assertDoesNotThrow(() -> MetaAdsService.validateDraft(restaurant, draft));
    }

}
