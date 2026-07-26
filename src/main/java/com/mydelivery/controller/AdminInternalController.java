package com.mydelivery.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.dto.admin.RestauranteAdminResponse;
import com.mydelivery.repository.AssinaturaRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.AdminService;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints admin protegidos por X-Admin-Secret (em vez de JWT ROLE_ADMIN).
 * Serve pra ops emergenciais quando não dá tempo/acesso pra pegar JWT admin.
 * SecurityConfig libera /api/admin-internal/** — proteção vem só do header.
 */
@RestController
@RequiredArgsConstructor
public class AdminInternalController {

    private final AdminService adminService;
    private final RestauranteRepository restauranteRepo;
    private final AssinaturaRepository assinaturaRepo;

    @Value("${mydelivery.admin.internal-secret:${ADMIN_INTERNAL_SECRET:}}")
    private String adminSecret;

    /** Bypass do /api/admin/restaurantes/{id}/desbloquear (que exige JWT admin). */
    @PostMapping("/api/admin-internal/restaurantes/{id}/desbloquear")
    public ResponseEntity<RestauranteAdminResponse> desbloquear(
            @PathVariable Long id,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        var resp = adminService.desbloquearRestaurante(id);
        // ATALHO EMERGENCIAL: adminService.desbloquear NÃO mexe em
        // trialExpiraEm. A lógica de "fase" no /assinatura/status usa
        // trialFim vs now — se estiver no passado, front bloqueia mesmo
        // com assinatura=ATIVA. Aqui empurramos trialExpiraEm/trialFim pra
        // +30 dias no futuro garantindo que a UI libere.
        try {
            restauranteRepo.findById(id).ifPresent(r -> {
                r.setTrialExpiraEm(java.time.LocalDateTime.now().plusDays(30));
                restauranteRepo.save(r);
            });
            assinaturaRepo.findByRestauranteId(id).ifPresent(a -> {
                a.setTrialFim(java.time.LocalDateTime.now().plusDays(30));
                assinaturaRepo.save(a);
            });
        } catch (Exception ignored) { /* fail-safe — desbloqueio principal já foi feito */ }
        return ResponseEntity.ok(resp);
    }

    private void validarSecret(String received) {
        if (adminSecret == null || adminSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "ADMIN_INTERNAL_SECRET não configurado");
        }
        if (!adminSecret.equals(received)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Segredo inválido");
        }
    }
}
