package com.mydelivery.controller;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.EntregaService;
import com.mydelivery.service.GoogleGeocodingService;
import com.mydelivery.service.GoogleGeocodingService.Localizacao;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GoogleGeocodingController {
    private final GoogleGeocodingService googleGeocodingService;
    private final EntregaService entregaService;
    private final RestauranteRepository restauranteRepo;

    /** Endpoint autenticado: só o restaurante localiza o endereço da própria loja. */
    @GetMapping("/api/restaurante/entrega/localizar-loja")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> localizarLoja(@AuthenticationPrincipal String email,
            @RequestParam String rua, @RequestParam(required = false, defaultValue = "") String numero,
            @RequestParam String bairro, @RequestParam String cidade, @RequestParam String uf,
            @RequestParam(required = false, defaultValue = "") String cep) {
        if (rua.isBlank() || bairro.isBlank() || cidade.isBlank() || uf.length() != 2
                || rua.length() > 200 || numero.length() > 120 || bairro.length() > 120
                || cidade.length() > 120 || cep.length() > 10) return ResponseEntity.badRequest().build();

        Restaurante restaurante = restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        String consulta = rua.trim() + ", " + numero.trim() + ", " + bairro.trim() + ", "
                + cidade.trim() + ", " + uf.trim() + (cep.isBlank() ? "" : ", " + cep.trim()) + ", Brasil";

        List<Localizacao> locais = googleGeocodingService.localizar(consulta);
        Localizacao local = locais.stream().filter(l -> corresponde(l, cidade, uf, cep)).findFirst().orElse(null);
        if (local != null) return resposta(local.lat(), local.lng(), "google");

        Double lat = restaurante.getEnderecoLatitude() == null ? null : restaurante.getEnderecoLatitude().doubleValue();
        Double lng = restaurante.getEnderecoLongitude() == null ? null : restaurante.getEnderecoLongitude().doubleValue();
        double[] fallback = entregaService.geocodificar(consulta, lat, lng);
        if (fallback == null) return ResponseEntity.notFound().build();
        return resposta(fallback[0], fallback[1], "osm");
    }

    private static boolean corresponde(Localizacao l, String cidade, String uf, String cep) {
        if (!l.cidade().isBlank() && !normalizar(l.cidade()).equals(normalizar(cidade))) return false;
        if (!l.uf().isBlank() && !l.uf().equalsIgnoreCase(uf)) return false;
        return cep.isBlank() || l.cep().isBlank() || digitos(l.cep()).equals(digitos(cep));
    }
    private static String normalizar(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
    private static String digitos(String s) { return s.replaceAll("\\D", ""); }
    private static ResponseEntity<Map<String, Object>> resposta(double lat, double lng, String provedor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lat", lat); body.put("lng", lng); body.put("provedor", provedor);
        return ResponseEntity.ok(body);
    }
}
