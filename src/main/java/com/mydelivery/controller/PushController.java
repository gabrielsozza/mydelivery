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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.model.PushSubscription;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.PushSubscriptionRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.WebPushService;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints Web Push:
 *
 *  GET  /api/restaurante/push/public-key  — frontend usa pra subscribe
 *  POST /api/restaurante/push/subscribe   — registra aparelho
 *  POST /api/restaurante/push/unsubscribe — remove aparelho
 *  GET  /api/restaurante/push/aparelhos   — lista aparelhos cadastrados
 *  POST /api/restaurante/push/teste       — envia notif de teste pra esse user
 *
 *  GET  /api/admin-internal/web-push/gerar-vapid — gera par novo (setup 1×)
 */
@RestController
@RequiredArgsConstructor
public class PushController {

    private final RestauranteRepository restauranteRepo;
    private final PushSubscriptionRepository pushRepo;
    private final WebPushService pushService;

    @Value("${mydelivery.admin.internal-secret:${ADMIN_INTERNAL_SECRET:}}")
    private String adminSecret;

    @GetMapping("/api/restaurante/push/public-key")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> publicKey() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("habilitado", pushService.isHabilitado());
        r.put("publicKey", pushService.getPublicKey());
        return ResponseEntity.ok(r);
    }

    @PostMapping("/api/restaurante/push/subscribe")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> subscribe(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String endpoint = str(body, "endpoint");
        Map<?, ?> keys = body.get("keys") instanceof Map<?, ?> m ? m : null;
        if (endpoint == null || keys == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endpoint/keys obrigatórios");
        }
        String p256dh = String.valueOf(keys.get("p256dh"));
        String auth = String.valueOf(keys.get("auth"));
        String rotulo = str(body, "rotulo");

        // Upsert por (restauranteId, endpoint)
        PushSubscription sub = pushRepo.findByRestauranteIdAndEndpoint(r.getId(), endpoint)
                .orElseGet(() -> PushSubscription.builder()
                        .restauranteId(r.getId())
                        .endpoint(endpoint)
                        .build());
        sub.setP256dh(p256dh);
        sub.setAuth(auth);
        if (rotulo != null && !rotulo.isBlank()) sub.setRotulo(rotulo);
        pushRepo.save(sub);
        return ResponseEntity.ok(Map.of("ok", true, "id", sub.getId()));
    }

    @PostMapping("/api/restaurante/push/unsubscribe")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> unsubscribe(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String endpoint = str(body, "endpoint");
        if (endpoint != null) {
            pushRepo.findByRestauranteIdAndEndpoint(r.getId(), endpoint)
                    .ifPresent(pushRepo::delete);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/api/restaurante/push/aparelhos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> listarAparelhos(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var lista = pushRepo.findByRestauranteId(r.getId()).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("rotulo", s.getRotulo());
            m.put("criadoEm", s.getCriadoEm() == null ? null : s.getCriadoEm().toString());
            return m;
        }).toList();
        return ResponseEntity.ok(lista);
    }

    @PostMapping("/api/restaurante/push/teste")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> teste(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        pushService.notificar(r.getId(),
                "🔔 Notificação de teste",
                "Se você está vendo isso, as notificações estão funcionando! Pode minimizar o navegador.",
                "/painel.html",
                "teste");
        return ResponseEntity.ok(Map.of("ok", true,
                "habilitado", pushService.isHabilitado()));
    }

    /** Setup inicial — chamada UMA vez pelo admin pra gerar VAPID e configurar env. */
    @GetMapping("/api/admin-internal/web-push/gerar-vapid")
    public ResponseEntity<Map<String, String>> gerarVapid(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        if (adminSecret == null || adminSecret.isBlank() || !adminSecret.equals(secret)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        try {
            return ResponseEntity.ok(pushService.gerarParVapid());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
}
