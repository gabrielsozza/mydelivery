package com.mydelivery.controller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.ifood.IfoodClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Endpoints pra o painel do restaurante gerenciar a integração com iFood.
 *
 *  GET   /api/restaurante/ifood/status          → estado atual da integração
 *  POST  /api/restaurante/ifood/conectar        → grava merchantId + ativa
 *  PATCH /api/restaurante/ifood/toggle          → liga/desliga sem perder merchantId
 *  POST  /api/restaurante/ifood/desconectar     → remove tudo
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class IfoodController {

    private final RestauranteRepository restauranteRepo;
    private final IfoodClient ifoodClient;

    @GetMapping("/api/restaurante/ifood/status")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> status(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        Map<String, Object> out = new HashMap<>();
        out.put("conectado", r.getIfoodMerchantId() != null && !r.getIfoodMerchantId().isBlank());
        out.put("ativo", Boolean.TRUE.equals(r.getIfoodIntegracaoAtiva()));
        out.put("merchantId", r.getIfoodMerchantId());
        if (r.getIfoodUltimoPollingEm() != null) {
            long minutos = Duration.between(r.getIfoodUltimoPollingEm(), LocalDateTime.now()).toMinutes();
            out.put("ultimoPollingEm", r.getIfoodUltimoPollingEm().toString());
            out.put("minutosDesdeUltimoPoll", minutos);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Body: { "merchantId": "uuid-do-ifood" }
     * Salva o merchantId e ativa a integração. O merchantId vem do Gestor
     * de Pedidos do iFood quando o dono autoriza nosso aplicativo lá.
     */
    @PostMapping("/api/restaurante/ifood/conectar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> conectar(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        String mid = body.get("merchantId");
        if (mid == null || mid.isBlank()) {
            throw new RuntimeException("merchantId é obrigatório");
        }
        r.setIfoodMerchantId(mid.trim());
        r.setIfoodIntegracaoAtiva(true);
        restauranteRepo.save(r);
        log.info("[iFood] Restaurante {} conectado com merchantId={}", r.getId(), mid);
        return ResponseEntity.ok(Map.of("ok", true, "merchantId", mid));
    }

    /** Body: { "ativo": true|false } — pausa sem perder merchantId. */
    @PatchMapping("/api/restaurante/ifood/toggle")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> toggle(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Boolean> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        boolean ativo = Boolean.TRUE.equals(body.get("ativo"));
        r.setIfoodIntegracaoAtiva(ativo);
        restauranteRepo.save(r);
        return ResponseEntity.ok(Map.of("ok", true, "ativo", ativo));
    }

    @PostMapping("/api/restaurante/ifood/desconectar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> desconectar(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        r.setIfoodMerchantId(null);
        r.setIfoodIntegracaoAtiva(false);
        r.setIfoodUltimoPollingEm(null);
        restauranteRepo.save(r);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Diagnóstico — testa OAuth do app. Usado pelo admin. */
    @GetMapping("/api/restaurante/ifood/ping")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> ping() {
        boolean ok = ifoodClient.ping();
        return ResponseEntity.ok(Map.of("apiOk", ok));
    }
}
