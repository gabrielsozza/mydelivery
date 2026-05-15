package com.mydelivery.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.dto.fidelidade.ProgramaFidelidadeDTO;
import com.mydelivery.dto.fidelidade.StatusClienteDTO;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.FidelidadeService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class FidelidadeController {

    private final FidelidadeService fidelidadeService;
    private final RestauranteRepository restauranteRepository;

    // ── Admin: get/set config ──────────────────────────────────────────────

    @GetMapping("/api/restaurante/fidelidade")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<ProgramaFidelidadeDTO> getConfig(@AuthenticationPrincipal String email) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(fidelidadeService.obterConfig(r.getId()));
    }

    @PutMapping("/api/restaurante/fidelidade")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<ProgramaFidelidadeDTO> salvarConfig(
            @AuthenticationPrincipal String email,
            @RequestBody ProgramaFidelidadeDTO dto) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(fidelidadeService.salvarConfig(r.getId(), dto));
    }

    // ── Público: status do cliente para o checkout ─────────────────────────

    @GetMapping("/api/fidelidade/{slug}/status")
    public ResponseEntity<StatusClienteDTO> statusCliente(
            @PathVariable String slug,
            @RequestParam(required = false) String telefone) {
        return ResponseEntity.ok(fidelidadeService.statusCliente(slug, telefone));
    }
}
