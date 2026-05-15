package com.mydelivery.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.dto.admin.BloquearRestauranteRequest;
import com.mydelivery.dto.admin.DashboardAdminResponse;
import com.mydelivery.dto.admin.RestauranteAdminResponse;
import com.mydelivery.job.AssinaturaJob;
import com.mydelivery.service.AdminService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final AssinaturaJob assinaturaJob;

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardAdminResponse> getDashboard() {
        return ResponseEntity.ok(adminService.getDashboard());
    }

    @GetMapping("/restaurantes")
    public ResponseEntity<List<RestauranteAdminResponse>> listarRestaurantes() {
        return ResponseEntity.ok(adminService.listarRestaurantes());
    }

    @GetMapping("/restaurantes/{id}")
    public ResponseEntity<RestauranteAdminResponse> buscarRestaurante(
            @PathVariable Long id) {
        return ResponseEntity.ok(adminService.buscarRestaurante(id));
    }

    @PostMapping("/restaurantes/{id}/bloquear")
    public ResponseEntity<RestauranteAdminResponse> bloquear(
            @PathVariable Long id,
            @Valid @RequestBody BloquearRestauranteRequest request) {
        return ResponseEntity.ok(adminService.bloquearRestaurante(id, request));
    }

    @PostMapping("/restaurantes/{id}/desbloquear")
    public ResponseEntity<RestauranteAdminResponse> desbloquear(
            @PathVariable Long id) {
        return ResponseEntity.ok(adminService.desbloquearRestaurante(id));
    }

    @PostMapping("/restaurantes/{id}/cancelar")
    public ResponseEntity<RestauranteAdminResponse> cancelar(
            @PathVariable Long id) {
        return ResponseEntity.ok(adminService.cancelarRestaurante(id));
    }

    @PostMapping("/jobs/verificar-trials")
    public ResponseEntity<Map<String, String>> executarJobTrial() {
        assinaturaJob.verificarTrialsExpirados();
        return ResponseEntity.ok(Map.of("mensagem", "Job executado com sucesso"));
    }
}