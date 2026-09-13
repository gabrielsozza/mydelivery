package com.mydelivery.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.PromoCampanha;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.whatsapp.PromoCampanhaService;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints das campanhas de promoção via WhatsApp. Todos exigem ROLE_RESTAURANTE.
 */
@RestController
@RequestMapping("/api/whatsapp/promo")
@RequiredArgsConstructor
public class PromoCampanhaController {

    private final PromoCampanhaService service;
    private final RestauranteRepository restauranteRepository;

    /** Preview: quantos clientes elegíveis existem hoje sem criar campanha. */
    @GetMapping("/estimativa")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> estimativa(
            @AuthenticationPrincipal String email,
            @org.springframework.web.bind.annotation.RequestParam(value = "minCompras", defaultValue = "1") int minCompras) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        int mc = Math.max(1, minCompras);
        int total = service.estimarDestinos(r, mc);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("elegiveis", total);
        out.put("minCompras", mc);
        return ResponseEntity.ok(out);
    }

    /** Body: { texto: "..." }. Cria + dispara imediatamente. */
    @PostMapping
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> criar(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        String texto = (String) body.getOrDefault("texto", "");
        // minCompras: 1 (default) = envia pra todos os cadastros da aba Clientes.
        // Dono pode pedir 2+ pra filtrar só recorrentes. Aceita como Number OU String.
        int minCompras = 1;
        Object mc = body.get("minCompras");
        if (mc instanceof Number n) minCompras = n.intValue();
        else if (mc != null) {
            try { minCompras = Integer.parseInt(mc.toString().trim()); } catch (Exception ignore) {}
        }
        PromoCampanha c = service.criarEDisparar(r, texto, minCompras);
        return ResponseEntity.ok(serializar(c));
    }

    @GetMapping
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> listar(
            @AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        List<Map<String, Object>> out = service.listar(r).stream()
                .map(this::serializar).toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> detalhe(
            @AuthenticationPrincipal String email, @PathVariable Long id) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(serializar(service.detalhe(r, id)));
    }

    @PostMapping("/{id}/pausar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> pausar(
            @AuthenticationPrincipal String email, @PathVariable Long id) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(serializar(service.pausar(r, id)));
    }

    @PostMapping("/{id}/retomar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> retomar(
            @AuthenticationPrincipal String email, @PathVariable Long id) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(serializar(service.retomar(r, id)));
    }

    @PostMapping("/{id}/cancelar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> cancelar(
            @AuthenticationPrincipal String email, @PathVariable Long id) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(serializar(service.cancelar(r, id)));
    }

    private Map<String, Object> serializar(PromoCampanha c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("texto", c.getTexto());
        m.put("status", c.getStatus().name());
        m.put("minCompras", c.getMinCompras());
        m.put("totalDestinos", c.getTotalDestinos());
        m.put("totalEnviados", c.getTotalEnviados());
        m.put("totalFalhas", c.getTotalFalhas());
        m.put("criadaEm", c.getCriadaEm());
        m.put("iniciadaEm", c.getIniciadaEm());
        m.put("concluidaEm", c.getConcluidaEm());
        return m;
    }
}
