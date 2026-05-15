package com.mydelivery.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.dto.estoque.CompraDTO;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.CompraService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CompraController {

    private final CompraService compraService;
    private final RestauranteRepository restauranteRepository;

    @GetMapping("/api/restaurante/compras")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<CompraDTO>> listar(@AuthenticationPrincipal String email) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(compraService.listarPorRestaurante(r.getId()));
    }

    @PostMapping("/api/restaurante/compras")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<CompraDTO> criar(@AuthenticationPrincipal String email,
                                            @RequestBody CompraDTO dto) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(compraService.criar(r.getId(), dto));
    }
}
