package com.mydelivery.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.dto.cupom.CupomDTO;
import com.mydelivery.dto.cupom.ValidarCupomRequest;
import com.mydelivery.dto.cupom.ValidarCupomResponse;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.CupomService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CupomController {

    private final CupomService cupomService;
    private final RestauranteRepository restauranteRepository;

    // ── ADMIN ──────────────────────────────────────────────────────────────

    @GetMapping("/api/restaurante/cupons")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<CupomDTO>> listar(@AuthenticationPrincipal String email) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(cupomService.listarPorRestaurante(r.getId()));
    }

    @PostMapping("/api/restaurante/cupons")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<CupomDTO> criar(
            @AuthenticationPrincipal String email,
            @RequestBody CupomDTO dto) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(cupomService.criar(r.getId(), dto));
    }

    @PutMapping("/api/restaurante/cupons/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<CupomDTO> atualizar(
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody CupomDTO dto) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(cupomService.atualizar(r.getId(), id, dto));
    }

    @DeleteMapping("/api/restaurante/cupons/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Void> deletar(
            @AuthenticationPrincipal String email,
            @PathVariable Long id) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        cupomService.deletar(r.getId(), id);
        return ResponseEntity.noContent().build();
    }

    // ── PÚBLICO: banner no cardápio + validação ────────────────────────────

    @GetMapping("/api/cupons/publico/{slug}")
    public ResponseEntity<List<CupomDTO>> publicos(@PathVariable String slug) {
        return ResponseEntity.ok(cupomService.listarPublicos(slug));
    }

    @PostMapping("/api/cupons/validar")
    public ResponseEntity<ValidarCupomResponse> validar(@RequestBody ValidarCupomRequest req) {
        return ResponseEntity.ok(cupomService.validar(req));
    }
}
