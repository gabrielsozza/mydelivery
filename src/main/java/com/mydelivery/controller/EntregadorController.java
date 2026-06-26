package com.mydelivery.controller;

import com.mydelivery.dto.entregador.*;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.EntregadorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/restaurante/{slug}/entregadores")
@RequiredArgsConstructor
public class EntregadorController {

    private final EntregadorService entregadorService;
    private final RestauranteRepository restauranteRepository;

    private Long resolverRestauranteId(String slug) {
        return restauranteRepository.findBySlug(slug)
                .map(r -> r.getId())
                .orElseThrow(() -> new RuntimeException("Restaurante nao encontrado"));
    }

    @PostMapping
    public ResponseEntity<EntregadorResponse> criar(@PathVariable String slug,
            @Valid @RequestBody NovoEntregadorRequest req) {
        return ResponseEntity.ok(entregadorService.criar(resolverRestauranteId(slug), req));
    }

    @GetMapping
    public ResponseEntity<List<EntregadorResponse>> listar(@PathVariable String slug) {
        return ResponseEntity.ok(entregadorService.listar(resolverRestauranteId(slug)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EntregadorResponse> editar(@PathVariable String slug,
            @PathVariable Long id, @Valid @RequestBody NovoEntregadorRequest req) {
        return ResponseEntity.ok(entregadorService.editar(resolverRestauranteId(slug), id, req));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<EntregadorResponse> atualizarStatus(@PathVariable String slug,
            @PathVariable Long id, @RequestBody AtualizarStatusEntregadorRequest req) {
        return ResponseEntity.ok(entregadorService.atualizarStatus(resolverRestauranteId(slug), id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> desativar(@PathVariable String slug, @PathVariable Long id) {
        entregadorService.desativar(resolverRestauranteId(slug), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Regera o PIN do entregador. Usado pelo painel do dono quando PIN
     * é comprometido (entregador saiu, vazou, etc.). Sessões JWT ativas
     * NÃO são invalidadas (subject é o entregadorId, não o PIN) — pra
     * logout imediato precisa app-side checar online flag ou rotacionar
     * JWT secret (fora de escopo).
     */
    @PostMapping("/{id}/pin/regenerar")
    public ResponseEntity<EntregadorResponse> regerarPin(@PathVariable String slug, @PathVariable Long id) {
        return ResponseEntity.ok(entregadorService.regerarPin(resolverRestauranteId(slug), id));
    }
}
