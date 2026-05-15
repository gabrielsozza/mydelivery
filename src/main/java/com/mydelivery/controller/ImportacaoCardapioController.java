package com.mydelivery.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mydelivery.dto.cardapio.ImportacaoConfirmRequest;
import com.mydelivery.dto.cardapio.ImportacaoPreviewDTO;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.ImportacaoCardapioService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ImportacaoCardapioController {

    private final ImportacaoCardapioService importacaoService;
    private final RestauranteRepository restauranteRepository;

    /**
     * Step 1: cliente sobe o arquivo, backend analisa e retorna preview.
     * NÃO toca no banco. Cliente revisa antes de confirmar.
     */
    @PostMapping(value = "/api/restaurante/cardapio/importar/preview", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<ImportacaoPreviewDTO> preview(
            @AuthenticationPrincipal String email,
            @RequestParam("arquivo") MultipartFile arquivo) {
        // Só valida que o restaurante existe (acesso via PreAuthorize)
        restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(importacaoService.analisar(arquivo));
    }

    /**
     * Step 2: cliente confirma o preview (eventualmente editado) e backend salva.
     * Modo "substituir" apaga cardápio existente antes — DESTRUTIVO.
     */
    @PostMapping("/api/restaurante/cardapio/importar/confirmar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Integer>> confirmar(
            @AuthenticationPrincipal String email,
            @RequestBody ImportacaoConfirmRequest req) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(importacaoService.confirmar(r.getId(), req));
    }
}
