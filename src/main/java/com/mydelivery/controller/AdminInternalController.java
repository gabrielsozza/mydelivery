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

    @Value("${mydelivery.admin.internal-secret:${ADMIN_INTERNAL_SECRET:}}")
    private String adminSecret;

    /** Bypass do /api/admin/restaurantes/{id}/desbloquear (que exige JWT admin). */
    @PostMapping("/api/admin-internal/restaurantes/{id}/desbloquear")
    public ResponseEntity<RestauranteAdminResponse> desbloquear(
            @PathVariable Long id,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        return ResponseEntity.ok(adminService.desbloquearRestaurante(id));
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
