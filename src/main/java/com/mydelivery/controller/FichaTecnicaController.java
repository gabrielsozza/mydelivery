package com.mydelivery.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.dto.estoque.FichaTecnicaDTO;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.FichaTecnicaService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class FichaTecnicaController {

    private final FichaTecnicaService fichaService;
    private final RestauranteRepository restauranteRepository;

    @GetMapping("/api/restaurante/produtos/{id}/ficha-tecnica")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<FichaTecnicaDTO> obter(@AuthenticationPrincipal String email,
                                                  @PathVariable Long id) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(fichaService.obter(r.getId(), id));
    }

    @PutMapping("/api/restaurante/produtos/{id}/ficha-tecnica")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<FichaTecnicaDTO> salvar(@AuthenticationPrincipal String email,
                                                   @PathVariable Long id,
                                                   @RequestBody FichaTecnicaDTO dto) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(fichaService.salvar(r.getId(), id, dto));
    }
}
