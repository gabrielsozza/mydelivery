package com.mydelivery.controller;

import com.mydelivery.repository.RestauranteRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/restaurante/google-measurement")
public class GoogleMeasurementController {
    private final RestauranteRepository restaurantes;
    public GoogleMeasurementController(RestauranteRepository restaurantes) { this.restaurantes = restaurantes; }
    public record Preference(boolean consent,
        @Pattern(regexp="[0-9]{1,30}\\.[0-9]{1,30}") String clientId, int version) {}

    @PutMapping
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Void> salvar(@AuthenticationPrincipal String email, @Valid @RequestBody Preference p) {
        var dono = restaurantes.findByUsuarioEmail(email);
        // Staff accounts must not overwrite the owner's attribution.
        if (dono.isEmpty()) return ResponseEntity.noContent().build();
        var r = dono.get();
        boolean granted = p.consent() && p.clientId() != null && p.version() == 2;
        if (granted && (!Boolean.TRUE.equals(r.getGoogleMeasurementConsent()) || r.getGoogleConsentAt() == null || !Integer.valueOf(2).equals(r.getGoogleConsentVersion())))
            r.setGoogleConsentAt(Instant.now());
        r.setGoogleMeasurementConsent(granted);
        r.setGoogleConsentVersion(granted ? 2 : null);
        if (granted) { if (r.getGoogleClientId() == null) r.setGoogleClientId(p.clientId()); }
        else { r.setGoogleClientId(null); r.setGoogleConsentAt(null); }
        restaurantes.save(r);
        return ResponseEntity.noContent().build();
    }
}
