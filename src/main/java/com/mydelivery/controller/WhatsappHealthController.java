package com.mydelivery.controller;

import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.model.Restaurante;
import com.mydelivery.model.WhatsappHealthLog;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.WhatsappHealthService;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints de saúde do WhatsApp.
 *
 *  - Restaurante (auth RESTAURANTE):
 *     GET  /api/restaurante/whatsapp/saude
 *     POST /api/restaurante/whatsapp/saude/reconectar
 *
 *  - Internos (X-Admin-Secret, chamados pelo admin-api):
 *     GET  /api/admin-internal/whatsapp/{instanceName}/saude
 *     GET  /api/admin-internal/whatsapp/{instanceName}/historico
 *     POST /api/admin-internal/whatsapp/{instanceName}/reconectar
 */
@RestController
@RequiredArgsConstructor
public class WhatsappHealthController {

    private final RestauranteRepository restauranteRepo;
    private final WhatsappInstanceRepository instanceRepo;
    private final WhatsappHealthService healthService;

    @Value("${mydelivery.admin.internal-secret:${ADMIN_INTERNAL_SECRET:}}")
    private String adminSecret;

    // ──────────── Endpoints do RESTAURANTE ────────────

    @GetMapping("/api/restaurante/whatsapp/saude")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> saudeRestaurante(@AuthenticationPrincipal String email) {
        WhatsappInstance inst = instanciaDoUsuario(email);
        if (inst == null) {
            return ResponseEntity.ok(Map.of("estado", "OFFLINE", "status", "NOVA",
                    "mensagem", "Instância WhatsApp não criada"));
        }
        return ResponseEntity.ok(healthService.resumoAtual(inst));
    }

    @PostMapping("/api/restaurante/whatsapp/saude/reconectar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> reconectarRestaurante(@AuthenticationPrincipal String email) {
        WhatsappInstance inst = instanciaDoUsuario(email);
        if (inst == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Instância não encontrada");
        boolean ok = healthService.tentarReconectar(inst);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", ok);
        r.put("estadoAtual", healthService.avaliarEstado(inst).name());
        r.put("mensagem", ok
                ? "Reconexão disparada. Aguarde até 1min e veja se voltou."
                : "Falha ao chamar a Evolution. Sessão pode precisar de novo QR.");
        return ResponseEntity.ok(r);
    }

    private WhatsappInstance instanciaDoUsuario(String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        return instanceRepo.findByRestauranteId(r.getId()).orElse(null);
    }

    // ──────────── Endpoints INTERNOS (admin-api → main-api) ────────────

    @GetMapping("/api/admin-internal/whatsapp/{instanceName}/saude")
    public ResponseEntity<Map<String, Object>> saudeAdmin(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        WhatsappInstance inst = instanceRepo.findByInstanceName(instanceName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Map<String, Object> out = new LinkedHashMap<>(healthService.resumoAtual(inst));
        out.put("instanceId", inst.getId());
        out.put("instanceName", inst.getInstanceName());
        out.put("phone", inst.getPhone());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/admin-internal/whatsapp/{instanceName}/historico")
    public ResponseEntity<List<Map<String, Object>>> historicoAdmin(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        WhatsappInstance inst = instanceRepo.findByInstanceName(instanceName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var logs = healthService.historico(inst.getId(), 24);
        var out = logs.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("em", h.getEm().toString());
            m.put("estado", h.getEstado().name());
            m.put("minutosSemMensagem", h.getMinutosSemMensagem());
            m.put("reconexaoDisparada", h.getReconexaoDisparada());
            return m;
        }).toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/admin-internal/whatsapp/{instanceName}/reconectar")
    public ResponseEntity<Map<String, Object>> reconectarAdmin(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        WhatsappInstance inst = instanceRepo.findByInstanceName(instanceName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean ok = healthService.tentarReconectar(inst);
        return ResponseEntity.ok(Map.of("ok", ok, "estadoAtual", healthService.avaliarEstado(inst).name()));
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
