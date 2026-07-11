package com.mydelivery.controller;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.EntregaService;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints do módulo de entrega por raio.
 *
 * <p>Painel (autenticado):
 * <ul>
 *   <li>{@code GET  /api/restaurante/entrega/config}      — modo + coords + zonas</li>
 *   <li>{@code PUT  /api/restaurante/entrega/config}      — salva modo + coords</li>
 *   <li>{@code PUT  /api/restaurante/entrega/zonas}       — substitui zonas</li>
 * </ul>
 *
 * <p>Público (cliente final no checkout):
 * <ul>
 *   <li>{@code GET  /api/publico/geocodificar}            — {@code ?q=} texto → lat/lng</li>
 *   <li>{@code GET  /api/publico/entrega/taxa}            — {@code ?slug=&lat=&lng=} → taxa aplicável</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class EntregaController {

    private final EntregaService entregaService;
    private final RestauranteRepository restauranteRepo;

    // ═════════════════════════════════════════════════════════════════════
    // PAINEL
    // ═════════════════════════════════════════════════════════════════════

    @GetMapping("/api/restaurante/entrega/config")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> obterConfig(@AuthenticationPrincipal String email) {
        Restaurante r = getRestaurante(email);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modoTaxa", r.getModoTaxa() == null ? "BAIRRO" : r.getModoTaxa());
        out.put("latitude", r.getEnderecoLatitude());
        out.put("longitude", r.getEnderecoLongitude());
        out.put("zonas", entregaService.listarZonas(r.getId()).stream().map(z -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", z.getId());
            m.put("raioKm", z.getRaioKm());
            m.put("taxa", z.getTaxa());
            m.put("ordem", z.getOrdem());
            return m;
        }).toList());
        return ResponseEntity.ok(out);
    }

    @PutMapping("/api/restaurante/entrega/config")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> salvarConfig(@AuthenticationPrincipal String email,
                                                              @RequestBody Map<String, Object> body) {
        Restaurante r = getRestaurante(email);
        String modo = strOf(body.get("modoTaxa"));
        if (modo != null && !modo.isBlank()) {
            String m = modo.trim().toUpperCase();
            if (!"BAIRRO".equals(m) && !"RAIO".equals(m)) {
                throw new RuntimeException("modoTaxa inválido — use BAIRRO ou RAIO");
            }
            r.setModoTaxa(m);
        }
        BigDecimal lat = decOf(body.get("latitude"));
        BigDecimal lng = decOf(body.get("longitude"));
        if (lat != null) r.setEnderecoLatitude(lat);
        if (lng != null) r.setEnderecoLongitude(lng);
        restauranteRepo.save(r);
        return obterConfig(email);
    }

    @PutMapping("/api/restaurante/entrega/zonas")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> salvarZonas(@AuthenticationPrincipal String email,
                                                             @RequestBody Map<String, Object> body) {
        Restaurante r = getRestaurante(email);
        List<Map<String, Object>> zonas = body.get("zonas") instanceof List<?>
                ? (List<Map<String, Object>>) body.get("zonas") : List.of();
        entregaService.substituirZonas(r, zonas);
        return obterConfig(email);
    }

    // ═════════════════════════════════════════════════════════════════════
    // PÚBLICO (cliente final)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Geocodifica string livre (rua + numero + bairro + cidade) pra
     * {@code {lat, lng}}. Chamado pelo cardápio quando cliente termina de
     * preencher o endereço. Retorna 404 se Nominatim não achou.
     */
    @GetMapping("/api/publico/geocodificar")
    public ResponseEntity<Map<String, Object>> geocodificar(@RequestParam("q") String query) {
        double[] c = entregaService.geocodificar(query);
        if (c == null) return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).build();
        return ResponseEntity.ok(Map.of("lat", c[0], "lng", c[1]));
    }

    /**
     * Retorna a taxa aplicável pro cliente no destino informado.
     * {@code slug} identifica o restaurante. Retorna {@code taxa=null} se
     * fora de raio ou restaurante em modo BAIRRO (front deve usar o fluxo
     * clássico de bairro nesse caso).
     */
    @GetMapping("/api/publico/entrega/taxa")
    public ResponseEntity<Map<String, Object>> taxaPorRaio(@RequestParam String slug,
                                                             @RequestParam double lat,
                                                             @RequestParam double lng) {
        Restaurante r = restauranteRepo.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modoTaxa", r.getModoTaxa() == null ? "BAIRRO" : r.getModoTaxa());
        if (!"RAIO".equalsIgnoreCase(r.getModoTaxa())) {
            out.put("taxa", null);
            out.put("mensagem", "Restaurante cobra por bairro — use o fluxo padrão");
            return ResponseEntity.ok(out);
        }
        BigDecimal taxa = entregaService.calcularTaxaPorRaio(r, lat, lng);
        out.put("taxa", taxa);
        if (taxa == null) {
            out.put("mensagem", "Fora da área de entrega");
        }
        return ResponseEntity.ok(out);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Restaurante getRestaurante(String email) {
        return restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
    }
    private static String strOf(Object o) { return o == null ? null : o.toString(); }
    private static BigDecimal decOf(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return null; }
    }
}
