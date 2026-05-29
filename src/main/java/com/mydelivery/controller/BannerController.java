package com.mydelivery.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.Banner;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.BannerRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD de banners promocionais do topo do cardápio.
 *
 *  - GET    /api/restaurante/banners                → painel (todos do dono)
 *  - POST   /api/restaurante/banners                → criar
 *  - PUT    /api/restaurante/banners/{id}           → editar
 *  - DELETE /api/restaurante/banners/{id}           → remover
 *  - PUT    /api/restaurante/banners/reordenar      → body: [id1, id2, ...]
 *  - GET    /api/cardapio/{slug}/banners            → público pro cliente final
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class BannerController {

    private final BannerRepository bannerRepo;
    private final ProdutoRepository produtoRepo;
    private final RestauranteRepository restauranteRepo;

    // ─── PAINEL DO RESTAURANTE ────────────────────────────────────────

    @GetMapping("/api/restaurante/banners")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> listar(@AuthenticationPrincipal String email) {
        // @Transactional(readOnly=true) é OBRIGATÓRIO aqui — toDTO(b) acessa
        // b.getProduto() (ManyToOne LAZY) e estoura LazyInitializationException
        // sem sessão aberta, virando 400 no GlobalExceptionHandler.
        Restaurante r = restauranteRepo.findByUsuarioEmail(email)
            .orElseThrow(() -> new RuntimeException("Restaurante não encontrado para o usuário autenticado"));
        return ResponseEntity.ok(
            bannerRepo.findByRestauranteIdOrderByOrdemAsc(r.getId()).stream().map(this::toDTO).toList()
        );
    }

    @PostMapping("/api/restaurante/banners")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> criar(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();

        String imagemUrl = (String) body.get("imagemUrl");
        if (imagemUrl == null || imagemUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("erro", "imagemUrl obrigatório"));
        }

        Produto produto = null;
        Object pid = body.get("produtoId");
        if (pid != null && !"".equals(pid.toString())) {
            Long produtoId = Long.valueOf(pid.toString());
            produto = produtoRepo.findById(produtoId)
                .filter(p -> p.getRestaurante().getId().equals(r.getId()))
                .orElseThrow(() -> new RuntimeException("Produto não encontrado ou não é deste restaurante"));
        }

        // Próxima ordem = max + 1
        int proxOrdem = bannerRepo.findByRestauranteIdOrderByOrdemAsc(r.getId()).stream()
            .mapToInt(Banner::getOrdem).max().orElse(-1) + 1;

        Banner b = Banner.builder()
            .restaurante(r)
            .imagemUrl(imagemUrl)
            .produto(produto)
            .ordem(proxOrdem)
            .ativo(true)
            .build();
        bannerRepo.save(b);
        log.info("[Banner] criado id={} rest={} produto={}", b.getId(), r.getId(),
                 produto != null ? produto.getId() : null);
        return ResponseEntity.ok(toDTO(b));
    }

    @PutMapping("/api/restaurante/banners/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> editar(
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        Banner b = bannerRepo.findById(id).orElseThrow(() -> new RuntimeException("Banner não encontrado"));
        if (!b.getRestaurante().getId().equals(r.getId())) {
            return ResponseEntity.status(403).body(Map.of("erro", "Banner não é deste restaurante"));
        }

        if (body.containsKey("imagemUrl")) {
            String img = (String) body.get("imagemUrl");
            if (img != null && !img.isBlank()) b.setImagemUrl(img);
        }
        if (body.containsKey("ativo")) {
            b.setAtivo(Boolean.TRUE.equals(body.get("ativo")));
        }
        if (body.containsKey("produtoId")) {
            Object pid = body.get("produtoId");
            if (pid == null || "".equals(pid.toString())) {
                b.setProduto(null);
            } else {
                Long produtoId = Long.valueOf(pid.toString());
                Produto p = produtoRepo.findById(produtoId)
                    .filter(x -> x.getRestaurante().getId().equals(r.getId()))
                    .orElseThrow(() -> new RuntimeException("Produto inválido"));
                b.setProduto(p);
            }
        }
        bannerRepo.save(b);
        return ResponseEntity.ok(toDTO(b));
    }

    @DeleteMapping("/api/restaurante/banners/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> remover(
            @AuthenticationPrincipal String email,
            @PathVariable Long id) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        Banner b = bannerRepo.findById(id).orElseThrow(() -> new RuntimeException("Banner não encontrado"));
        if (!b.getRestaurante().getId().equals(r.getId())) {
            return ResponseEntity.status(403).body(Map.of("erro", "Banner não é deste restaurante"));
        }
        bannerRepo.delete(b);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Body: lista de IDs na nova ordem. */
    @PutMapping("/api/restaurante/banners/reordenar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> reordenar(
            @AuthenticationPrincipal String email,
            @RequestBody List<Long> idsNaOrdem) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        if (idsNaOrdem == null || idsNaOrdem.isEmpty()) {
            return ResponseEntity.ok(Map.of("ok", true));
        }
        var existentes = bannerRepo.findByRestauranteIdOrderByOrdemAsc(r.getId());
        var porId = new HashMap<Long, Banner>();
        for (Banner b : existentes) porId.put(b.getId(), b);

        int ord = 0;
        for (Long id : idsNaOrdem) {
            Banner b = porId.get(id);
            if (b == null) continue; // ID estranho — ignora
            b.setOrdem(ord++);
        }
        bannerRepo.saveAll(porId.values());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ─── PÚBLICO (cardápio do cliente final) ──────────────────────────

    @GetMapping("/api/cardapio/{slug}/banners")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> publicoBanners(@PathVariable String slug) {
        // @Transactional(readOnly=true) — mesma razão do listar() (LazyInit em produto).
        Restaurante r = restauranteRepo.findBySlug(slug)
            .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        return ResponseEntity.ok(
            bannerRepo.findByRestauranteIdAndAtivoTrueOrderByOrdemAsc(r.getId()).stream()
                .map(this::toDTO).toList()
        );
    }

    // ─── HELPERS ──────────────────────────────────────────────────────

    private Map<String, Object> toDTO(Banner b) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", b.getId());
        m.put("imagemUrl", b.getImagemUrl());
        m.put("ordem", b.getOrdem());
        m.put("ativo", b.getAtivo());
        if (b.getProduto() != null) {
            m.put("produtoId", b.getProduto().getId());
            m.put("produtoNome", b.getProduto().getNome());
        } else {
            m.put("produtoId", null);
            m.put("produtoNome", null);
        }
        return m;
    }
}
