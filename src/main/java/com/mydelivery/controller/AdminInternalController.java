package com.mydelivery.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import com.mydelivery.dto.admin.BloquearRestauranteRequest;
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

    /**
     * Bypass do /api/admin/restaurantes/{id}/desbloquear (que exige JWT admin).
     * Aceita {@code ?dias=N} (default 30). O AdminService.desbloquearRestaurante(id, dias)
     * já cuida de empurrar trialExpiraEm + trialFim + proximaCobranca pra now+dias.
     */
    @PostMapping("/api/admin-internal/restaurantes/{id}/desbloquear")
    public ResponseEntity<RestauranteAdminResponse> desbloquear(
            @PathVariable Long id,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestParam(value = "dias", required = false, defaultValue = "30") Integer dias) {
        validarSecret(secret);
        int d = (dias == null || dias < 1) ? 30 : Math.min(dias, 365);
        return ResponseEntity.ok(adminService.desbloquearRestaurante(id, d));
    }

    /**
     * Bypass do /api/admin/restaurantes/{id}/bloquear (que exige JWT admin).
     * Body: {@code {"motivo":"..."}}
     */
    @PostMapping("/api/admin-internal/restaurantes/{id}/bloquear")
    public ResponseEntity<RestauranteAdminResponse> bloquear(
            @PathVariable Long id,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestBody(required = false) Map<String, Object> body) {
        validarSecret(secret);
        String motivo = body != null && body.get("motivo") != null
                ? String.valueOf(body.get("motivo"))
                : "Bloqueio manual pelo admin";
        BloquearRestauranteRequest req = new BloquearRestauranteRequest();
        req.setMotivo(motivo);
        return ResponseEntity.ok(adminService.bloquearRestaurante(id, req));
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
