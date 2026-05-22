package com.mydelivery.controller;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.model.ComplementoGrupo;
import com.mydelivery.model.ComplementoItem;
import com.mydelivery.model.Produto;
import com.mydelivery.repository.ComplementoGrupoRepository;
import com.mydelivery.repository.ComplementoItemRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;

/**
 * Grupos de complementos / adicionais por produto (estilo iFood).
 *
 * Modelo:
 *   Produto 1—N ComplementoGrupo 1—N ComplementoItem
 *
 * Cada grupo tem nome ("Tamanho", "Frutas", "Caldas"), regra de quantidade
 * (obrigatório, min, max) e seus itens com preço próprio (0 = grátis).
 *
 * Endpoints admin (auth RESTAURANTE):
 *   GET    /api/produtos/{produtoId}/complementos
 *   POST   /api/produtos/{produtoId}/complementos              cria grupo
 *   PUT    /api/complementos/grupos/{grupoId}                  edita grupo + itens
 *   DELETE /api/complementos/grupos/{grupoId}
 *
 * Endpoint público (cliente final no cardápio):
 *   GET    /public/produtos/{produtoId}/complementos
 */
@RestController
@RequiredArgsConstructor
public class ComplementoController {

    private final ComplementoGrupoRepository grupoRepo;
    private final ComplementoItemRepository itemRepo;
    private final ProdutoRepository produtoRepo;
    private final RestauranteRepository restauranteRepo;

    // ── ADMIN ──

    @GetMapping("/api/produtos/{produtoId}/complementos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> listar(
            @AuthenticationPrincipal String email,
            @PathVariable Long produtoId) {
        Produto p = checkOwner(email, produtoId);
        return ResponseEntity.ok(grupoRepo.findByProdutoIdOrderByIdAsc(p.getId())
                .stream().map(this::serializar).toList());
    }

    @PostMapping("/api/produtos/{produtoId}/complementos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> criarGrupo(
            @AuthenticationPrincipal String email,
            @PathVariable Long produtoId,
            @RequestBody Map<String, Object> body) {
        Produto p = checkOwner(email, produtoId);
        ComplementoGrupo g = ComplementoGrupo.builder()
                .produto(p)
                .nome(strReq(body, "nome"))
                .obrigatorio(boolOr(body, "obrigatorio", false))
                .minEscolhas(intOr(body, "minEscolhas", 0))
                .maxEscolhas(intOr(body, "maxEscolhas", 1))
                .itens(new java.util.ArrayList<>())  // garante coleção mutável
                .build();
        // Adiciona itens ANTES de salvar — cascade.ALL persiste tudo numa só
        adicionarItensA(g, body.get("itens"));
        g = grupoRepo.saveAndFlush(g);
        return ResponseEntity.status(HttpStatus.CREATED).body(serializar(g));
    }

    @PutMapping("/api/complementos/grupos/{grupoId}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> editarGrupo(
            @AuthenticationPrincipal String email,
            @PathVariable Long grupoId,
            @RequestBody Map<String, Object> body) {
        ComplementoGrupo g = grupoRepo.findById(grupoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        checkOwner(email, g.getProduto().getId());

        if (body.containsKey("nome"))         g.setNome(strReq(body, "nome"));
        if (body.containsKey("obrigatorio"))  g.setObrigatorio(boolOr(body, "obrigatorio", false));
        if (body.containsKey("minEscolhas"))  g.setMinEscolhas(intOr(body, "minEscolhas", 0));
        if (body.containsKey("maxEscolhas"))  g.setMaxEscolhas(intOr(body, "maxEscolhas", 1));

        // ── Substituição completa dos itens (jeito canônico com orphanRemoval=true) ──
        // Antes: itemRepo.delete() em cada antigo + itemRepo.save() em cada novo. Isso
        // entrava em conflito com a coleção EAGER gerenciada do Hibernate (os antigos
        // continuavam em g.getItens() depois do delete via repo). Resultado: ao reabrir
        // o produto, items deletados voltavam a aparecer e até duplicavam.
        // Agora: clear() na coleção dispara orphanRemoval (DELETE no banco), e os novos
        // são adicionados na própria coleção. Cascade.ALL no save persiste tudo. Flush
        // garante que SQL roda antes do serializar.
        if (body.containsKey("itens")) {
            if (g.getItens() == null) g.setItens(new java.util.ArrayList<>());
            g.getItens().clear();
            adicionarItensA(g, body.get("itens"));
        }
        g = grupoRepo.saveAndFlush(g);
        return ResponseEntity.ok(serializar(g));
    }

    /** Helper: parse o body.itens (List<Map>) e adiciona à coleção do grupo. */
    private void adicionarItensA(ComplementoGrupo g, Object itensRaw) {
        if (!(itensRaw instanceof List<?> ll)) return;
        if (g.getItens() == null) g.setItens(new java.util.ArrayList<>());
        for (Object io : ll) {
            if (!(io instanceof Map<?,?> im)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> imap = (Map<String, Object>) im;
            ComplementoItem it = ComplementoItem.builder()
                    .grupo(g)
                    .nome(strReq(imap, "nome"))
                    .precoAdicional(decOr(imap, "precoAdicional", BigDecimal.ZERO))
                    .ativo(boolOr(imap, "ativo", true))
                    .build();
            g.getItens().add(it);
        }
    }

    @DeleteMapping("/api/complementos/grupos/{grupoId}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Void> deletarGrupo(
            @AuthenticationPrincipal String email,
            @PathVariable Long grupoId) {
        ComplementoGrupo g = grupoRepo.findById(grupoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        checkOwner(email, g.getProduto().getId());
        grupoRepo.delete(g);
        return ResponseEntity.noContent().build();
    }

    // ── PÚBLICO (cardápio) ──

    @GetMapping("/public/produtos/{produtoId}/complementos")
    public ResponseEntity<List<Map<String, Object>>> listarPublico(@PathVariable Long produtoId) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .body(grupoRepo.findByProdutoIdOrderByIdAsc(produtoId)
                        .stream().map(this::serializar).toList());
    }

    // ── helpers ──

    private Produto checkOwner(String email, Long produtoId) {
        Produto p = produtoRepo.findById(produtoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado"));
        Long meuRestId = restauranteRepo.findByUsuarioEmail(email).orElseThrow().getId();
        if (!p.getRestaurante().getId().equals(meuRestId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return p;
    }

    private Map<String, Object> serializar(ComplementoGrupo g) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", g.getId());
        out.put("nome", g.getNome());
        out.put("obrigatorio", Boolean.TRUE.equals(g.getObrigatorio()));
        out.put("minEscolhas", g.getMinEscolhas() != null ? g.getMinEscolhas() : 0);
        out.put("maxEscolhas", g.getMaxEscolhas() != null ? g.getMaxEscolhas() : 1);
        List<Map<String, Object>> itens = g.getItens() == null ? List.of()
                : g.getItens().stream()
                    .filter(i -> Boolean.TRUE.equals(i.getAtivo()))
                    .map(i -> {
                        Map<String, Object> mi = new HashMap<>();
                        mi.put("id", i.getId());
                        mi.put("nome", i.getNome());
                        mi.put("precoAdicional", i.getPrecoAdicional() != null ? i.getPrecoAdicional() : BigDecimal.ZERO);
                        mi.put("ativo", Boolean.TRUE.equals(i.getAtivo()));
                        return mi;
                    }).toList();
        out.put("itens", itens);
        return out;
    }

    private static String strReq(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, k + " é obrigatório");
        String s = v.toString().trim();
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, k + " não pode ser vazio");
        return s;
    }
    private static boolean boolOr(Map<String, Object> m, String k, boolean d) {
        Object v = m.get(k);
        if (v instanceof Boolean b) return b;
        if (v != null) return Boolean.parseBoolean(v.toString());
        return d;
    }
    private static int intOr(Map<String, Object> m, String k, int d) {
        Object v = m.get(k);
        if (v == null) return d;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return d; }
    }
    private static BigDecimal decOr(Map<String, Object> m, String k, BigDecimal d) {
        Object v = m.get(k);
        if (v == null) return d;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return d; }
    }
}
