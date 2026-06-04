package com.mydelivery.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.whatsapp.WhatsappIncidenteService;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints de incidentes e alertas.
 *
 * Restaurante:
 *   GET /api/restaurante/whatsapp/alerta-atual      — banner condicional no painel
 *
 * Admin (via X-Admin-Secret):
 *   GET  /api/admin-internal/whatsapp/incidentes?aberto=true
 *   GET  /api/admin-internal/whatsapp/incidentes/alertas-ativos
 *   GET  /api/admin-internal/whatsapp/acoes?incidente=N
 *   POST /api/admin-internal/whatsapp/incidentes/{id}/ack
 */
@RestController
@RequiredArgsConstructor
public class WhatsappIncidenteController {

    private final RestauranteRepository restauranteRepo;
    private final WhatsappIncidenteService incidenteService;

    @Value("${mydelivery.admin.internal-secret:${ADMIN_INTERNAL_SECRET:}}")
    private String adminSecret;

    // ──────────── RESTAURANTE ────────────

    @GetMapping("/api/restaurante/whatsapp/alerta-atual")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<?> alertaAtual(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Map<String, Object> alerta = incidenteService.alertaAtualDoRestaurante(r.getId());
        if (alerta == null) return ResponseEntity.ok(Map.of("temAlerta", false));
        return ResponseEntity.ok(Map.of("temAlerta", true, "alerta", alerta));
    }

    // ──────────── ADMIN-INTERNAL ────────────

    @GetMapping("/api/admin-internal/whatsapp/incidentes")
    public ResponseEntity<List<Map<String, Object>>> listarIncidentes(
            @RequestParam(defaultValue = "false") boolean aberto,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        return ResponseEntity.ok(incidenteService.listarIncidentes(aberto));
    }

    @GetMapping("/api/admin-internal/whatsapp/incidentes/alertas-ativos")
    public ResponseEntity<List<Map<String, Object>>> alertasAtivos(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        return ResponseEntity.ok(incidenteService.listarAlertasAtivos());
    }

    @GetMapping("/api/admin-internal/whatsapp/acoes")
    public ResponseEntity<List<Map<String, Object>>> listarAcoes(
            @RequestParam(required = false) Long incidente,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        return ResponseEntity.ok(incidenteService.listarAcoes(incidente));
    }

    @PostMapping("/api/admin-internal/whatsapp/incidentes/{id}/ack")
    public ResponseEntity<Map<String, Object>> ack(
            @PathVariable Long id,
            @RequestParam(defaultValue = "admin") String operador,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        boolean ok = incidenteService.ack(id, operador);
        return ResponseEntity.ok(Map.of("ok", ok));
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
