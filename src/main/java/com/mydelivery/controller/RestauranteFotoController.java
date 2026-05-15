package com.mydelivery.controller;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.CloudinaryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Upload de logo e capa do restaurante.
 *
 * Centralizado no Cloudinary — nenhum byte toca o disco do servidor.
 * Estrutura no Cloudinary: mydelivery/produtos/rest-{id}/logo-or-capa/...
 *
 * Backend é apenas intermediário: recebe multipart, valida, envia ao Cloudinary
 * e persiste a `secure_url` retornada em Restaurante.logoUrl/capaUrl.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RestauranteFotoController {

    private final RestauranteRepository restauranteRepository;
    private final CloudinaryService cloudinary;

    @PostMapping(value = "/api/restaurante/foto", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, String>> upload(
            @AuthenticationPrincipal String email,
            @RequestParam("tipo") String tipo,
            @RequestParam("arquivo") MultipartFile arquivo) {

        if (!"logo".equals(tipo) && !"capa".equals(tipo)) {
            throw new RuntimeException("Tipo inválido. Use 'logo' ou 'capa'.");
        }

        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();

        // Subpasta organizada por restaurante + tipo de imagem.
        // CloudinaryService valida tamanho (5MB), extensão e tipo real.
        String subfolder = "rest-" + r.getId() + "/" + tipo;
        String url = cloudinary.upload(arquivo, subfolder);

        if ("logo".equals(tipo)) r.setLogoUrl(url); else r.setCapaUrl(url);
        restauranteRepository.save(r);

        log.info("[Foto] Restaurante #{} salvou {} no Cloudinary: {}", r.getId(), tipo, url);
        return ResponseEntity.ok(Map.of("url", url, "tipo", tipo));
    }
}
