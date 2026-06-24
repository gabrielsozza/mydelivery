package com.mydelivery.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.dto.cardapio.CategoriaComProdutosResponse;
import com.mydelivery.dto.cardapio.CategoriaRequest;
import com.mydelivery.dto.cardapio.ProdutoRequest;
import com.mydelivery.dto.cardapio.ProdutoResponse;
import com.mydelivery.model.Categoria;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.CardapioService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CardapioController {

    private final CardapioService cardapioService;
    private final RestauranteRepository restauranteRepository;
    private final com.mydelivery.repository.CategoriaRepository categoriaRepository;

    // ─── PÚBLICO ──────────────────────────────────────────────────────────
    @GetMapping("/api/cardapio/{slug}")
    public ResponseEntity<List<CategoriaComProdutosResponse>> getCardapioPublico(
            @PathVariable String slug) {
        // no-cache: garante que alterações de produto/categoria/foto feitas no
        // painel apareçam no cardápio do cliente sem precisar de Ctrl+F5.
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .body(cardapioService.getCardapioPublico(slug));
    }

    @GetMapping("/api/restaurante/publico/{slug}/horarios")
    public ResponseEntity<?> horariosDisponiveis(@PathVariable String slug) {
        Restaurante r = restauranteRepository.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return ResponseEntity.ok(Map.of(
                "ativo",        Boolean.TRUE.equals(r.getAgendamentoAtivo()),
                "slots",        r.getAgendamentoSlots() != null ? r.getAgendamentoSlots() : List.of(),
                "intervalo",    r.getAgendamentoIntervalo() != null ? r.getAgendamentoIntervalo() : 30,
                "antecedencia", r.getAgendamentoAntecedencia() != null ? r.getAgendamentoAntecedencia() : 1
        ));
    }

    // ─── PRIVADO ──────────────────────────────────────────────────────────
    @GetMapping("/api/restaurante/{slug}/categorias")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Categoria>> getCategorias(
            @PathVariable String slug,
            @AuthenticationPrincipal String email) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.getCategorias(restauranteId));
    }

    @PostMapping("/api/restaurante/{slug}/categorias")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Categoria> criarCategoria(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @Valid @RequestBody CategoriaRequest request) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.criarCategoria(restauranteId, request));
    }

    /** Body: [id1, id2, id3, ...] na nova ordem. Multi-tenant (filtra por restaurante do email). */
    @PutMapping("/api/restaurante/{slug}/categorias/reordenar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<java.util.Map<String, Object>> reordenarCategorias(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @RequestBody java.util.List<Long> idsNaOrdem) {
        Long restauranteId = getRestauranteId(email);
        var existentes = categoriaRepository.findByRestauranteIdOrderByOrdemAsc(restauranteId);
        var porId = new java.util.HashMap<Long, com.mydelivery.model.Categoria>();
        for (var c : existentes) porId.put(c.getId(), c);
        int ord = 0;
        for (Long id : idsNaOrdem) {
            var c = porId.get(id);
            if (c == null) continue; // ignora IDs estranhos (multi-tenant safe)
            c.setOrdem(ord++);
        }
        categoriaRepository.saveAll(porId.values());
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }

    @PutMapping("/api/restaurante/{slug}/categorias/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Categoria> atualizarCategoria(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @Valid @RequestBody CategoriaRequest request) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.atualizarCategoria(restauranteId, id, request));
    }

    @DeleteMapping("/api/restaurante/{slug}/categorias/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Void> deletarCategoria(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id) {
        Long restauranteId = getRestauranteId(email);
        cardapioService.deletarCategoria(restauranteId, id);
        return ResponseEntity.noContent().build();
    }

    /** Duplica uma categoria com TODOS os produtos dentro (incluindo grupos
     *  de complementos e itens). Atômico — se algum produto falhar, nada salva.
     *  Útil pra restaurante criar variantes ("Açaí", "Açaí Promocional", etc)
     *  sem refazer cadastro item por item. */
    @PostMapping("/api/restaurante/{slug}/categorias/{id}/duplicar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Categoria> duplicarCategoria(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.duplicarCategoria(restauranteId, id));
    }

    @GetMapping("/api/restaurante/{slug}/categorias/{categoriaId}/produtos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<ProdutoResponse>> getProdutosPorCategoria(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long categoriaId) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.getProdutosPorCategoria(restauranteId, categoriaId));
    }

    @GetMapping("/api/restaurante/{slug}/produtos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<ProdutoResponse>> getProdutos(
            @PathVariable String slug,
            @AuthenticationPrincipal String email) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.getProdutos(restauranteId));
    }

    @PostMapping("/api/restaurante/{slug}/produtos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<ProdutoResponse> criarProduto(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @Valid @RequestBody ProdutoRequest request) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.criarProduto(restauranteId, request));
    }

    @PutMapping("/api/restaurante/{slug}/produtos/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<ProdutoResponse> atualizarProduto(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @Valid @RequestBody ProdutoRequest request) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.atualizarProduto(restauranteId, id, request));
    }

    @DeleteMapping("/api/restaurante/{slug}/produtos/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Void> deletarProduto(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id) {
        Long restauranteId = getRestauranteId(email);
        cardapioService.deletarProduto(restauranteId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Toggle de disponibilidade do produto (sem precisar mandar o ProdutoRequest
     * inteiro, que exige nome/preco/categoria etc). Caso de uso: o dono
     * pausa um produto que acabou no estoque, depois reativa.
     *
     * Aceita body em duas variacoes pra compat com clientes diferentes:
     *   { "disponivel": true|false }   ← preferido
     *   { "ativo":      true|false }   ← alias (frontend antigo)
     *
     * Multi-tenant safe — checa que o produto pertence ao restaurante do email.
     */
    @PatchMapping("/api/restaurante/{slug}/produtos/{id}/disponivel")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<ProdutoResponse> atualizarDisponibilidade(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody java.util.Map<String, Object> body) {
        Long restauranteId = getRestauranteId(email);
        Object v = body.get("disponivel");
        if (v == null) v = body.get("ativo");
        boolean disponivel = (v instanceof Boolean b) ? b
                : v != null && Boolean.parseBoolean(v.toString());
        return ResponseEntity.ok(cardapioService.atualizarDisponibilidade(restauranteId, id, disponivel));
    }

    /** Alias /ativo — frontend antigo (cardapio.html) chamava este path.
     *  Mantemos pra nao quebrar a versao cacheada no browser do dono. */
    @PatchMapping("/api/restaurante/{slug}/produtos/{id}/ativo")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<ProdutoResponse> atualizarAtivoAlias(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody java.util.Map<String, Object> body) {
        return atualizarDisponibilidade(slug, email, id, body);
    }

    /** Body: [id1, id2, ...] na nova ordem dos produtos da categoria. Multi-tenant safe. */
    @PutMapping("/api/restaurante/{slug}/categorias/{categoriaId}/produtos/reordenar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> reordenarProdutos(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long categoriaId,
            @RequestBody List<Long> idsNaOrdem) {
        Long restauranteId = getRestauranteId(email);
        cardapioService.reordenarProdutosNaCategoria(restauranteId, categoriaId, idsNaOrdem);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ─── Helper ───────────────────────────────────────────────────────────
    private Long getRestauranteId(String email) {
        return restauranteRepository
                .findByUsuarioEmail(email)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"))
                .getId();
    }
}
