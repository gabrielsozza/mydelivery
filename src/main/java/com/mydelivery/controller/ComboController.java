package com.mydelivery.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
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

import com.mydelivery.model.ComboItem;
import com.mydelivery.model.ComplementoGrupo;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.CategoriaRepository;
import com.mydelivery.repository.ComboItemRepository;
import com.mydelivery.repository.ComplementoGrupoRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Combos inteligentes — um produto-combo é um Produto com tipo=COMBO que
 * referencia outros produtos via {@link ComboItem}.
 *
 * Estratégia de modelagem:
 *  - O combo é, no banco, apenas um Produto normal (mantém categoria, preço,
 *    foto, ordem, disponivel etc.) — então NÃO quebra nenhum código existente
 *    que itera produtos por categoria. Só ganha o campo {@code tipo=COMBO}
 *    e uma coleção de filhos via ComboItem.
 *  - Os complementos dos filhos vêm dos próprios filhos (cada Açaí 500ml
 *    no combo expõe os mesmos complementos que ele tem como produto solto).
 *  - O preço do combo é o do próprio Produto.preco — soma dos filhos não
 *    importa, é tabelado pelo dono.
 *
 * Endpoints admin:
 *   POST   /api/restaurante/combos                 → cria combo
 *   PUT    /api/restaurante/combos/{comboId}       → atualiza combo + filhos
 *   GET    /api/restaurante/combos/{comboId}       → detalhe admin
 *   DELETE /api/restaurante/combos/{comboId}       → remove combo (e filhos via FK)
 *
 * Endpoint público (cardápio do cliente final):
 *   GET    /public/produtos/{produtoId}/combo      → estrutura expandida pro
 *                                                    cliente escolher itens
 *
 * Body de criação/edição:
 * {
 *   "nome": "Combo Açaí Família",
 *   "descricao": "...",
 *   "preco": 45.90,
 *   "categoriaId": 12,
 *   "fotoUrl": "...",
 *   "itens": [
 *     { "produtoFilhoId": 7, "quantidade": 2, "ordem": 0 },
 *     { "produtoFilhoId": 9, "quantidade": 1, "ordem": 1 }
 *   ]
 * }
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ComboController {

    private final ProdutoRepository produtoRepo;
    private final ComboItemRepository comboItemRepo;
    private final ComplementoGrupoRepository grupoComplementoRepo;
    private final CategoriaRepository categoriaRepo;
    private final RestauranteRepository restauranteRepo;

    // ── ADMIN: criar ──

    @PostMapping("/api/restaurante/combos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> criar(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        Restaurante r = meuRestaurante(email);

        Produto combo = Produto.builder()
                .restaurante(r)
                .nome(strReq(body, "nome"))
                .descricao(strOr(body, "descricao", null))
                .preco(decOr(body, "preco", BigDecimal.ZERO))
                .fotoUrl(strOr(body, "fotoUrl", null))
                .ordem(intOr(body, "ordem", 0))
                .disponivel(boolOr(body, "disponivel", true))
                .destaque(boolOr(body, "destaque", false))
                .tipo(Produto.Tipo.COMBO)
                .build();

        // Categoria opcional — se vier, valida ownership do restaurante
        Long catId = longOrNull(body, "categoriaId");
        if (catId != null) {
            var cat = categoriaRepo.findById(catId).orElse(null);
            if (cat != null && cat.getRestaurante() != null
                    && cat.getRestaurante().getId().equals(r.getId())) {
                combo.setCategoria(cat);
            }
        }

        combo = produtoRepo.saveAndFlush(combo);
        salvarItensDoCombo(combo, r, body.get("itens"));

        return ResponseEntity.status(HttpStatus.CREATED).body(serializarCombo(combo, true));
    }

    // ── ADMIN: editar ──

    @PutMapping("/api/restaurante/combos/{comboId}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> editar(
            @AuthenticationPrincipal String email,
            @PathVariable Long comboId,
            @RequestBody Map<String, Object> body) {
        Restaurante r = meuRestaurante(email);
        Produto combo = produtoRepo.findById(comboId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!combo.getRestaurante().getId().equals(r.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (combo.getTipo() != Produto.Tipo.COMBO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Produto não é combo (tipo=" + combo.getTipo() + ")");
        }

        if (body.containsKey("nome"))      combo.setNome(strReq(body, "nome"));
        if (body.containsKey("descricao")) combo.setDescricao(strOr(body, "descricao", null));
        if (body.containsKey("preco"))     combo.setPreco(decOr(body, "preco", combo.getPreco()));
        if (body.containsKey("fotoUrl"))   combo.setFotoUrl(strOr(body, "fotoUrl", null));
        if (body.containsKey("disponivel")) combo.setDisponivel(boolOr(body, "disponivel", true));
        if (body.containsKey("destaque"))   combo.setDestaque(boolOr(body, "destaque", false));
        if (body.containsKey("ordem"))      combo.setOrdem(intOr(body, "ordem", 0));

        combo = produtoRepo.saveAndFlush(combo);

        // Substituição completa dos filhos (mais simples e previsível que
        // diff). Em combos típicos são <10 filhos, performance não é problema.
        if (body.containsKey("itens")) {
            comboItemRepo.deleteByComboId(combo.getId());
            salvarItensDoCombo(combo, r, body.get("itens"));
        }

        return ResponseEntity.ok(serializarCombo(combo, true));
    }

    // ── ADMIN: detalhe ──

    @GetMapping("/api/restaurante/combos/{comboId}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> detalhe(
            @AuthenticationPrincipal String email,
            @PathVariable Long comboId) {
        Restaurante r = meuRestaurante(email);
        Produto combo = produtoRepo.findById(comboId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!combo.getRestaurante().getId().equals(r.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return ResponseEntity.ok(serializarCombo(combo, true));
    }

    // ── ADMIN: delete ──

    @DeleteMapping("/api/restaurante/combos/{comboId}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Void> deletar(
            @AuthenticationPrincipal String email,
            @PathVariable Long comboId) {
        Restaurante r = meuRestaurante(email);
        Produto combo = produtoRepo.findById(comboId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!combo.getRestaurante().getId().equals(r.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        comboItemRepo.deleteByComboId(combo.getId());
        produtoRepo.delete(combo);
        return ResponseEntity.noContent().build();
    }

    // ── PÚBLICO: cardápio expande combo ──

    /**
     * Devolve a estrutura completa do combo pro frontend renderizar a
     * tela de seleção: cada filho com seus grupos de complementos,
     * expandido por quantidade (Açaí 500ml #1, #2, …).
     */
    @GetMapping("/public/produtos/{produtoId}/combo")
    public ResponseEntity<Map<String, Object>> publicoCombo(@PathVariable Long produtoId) {
        Produto combo = produtoRepo.findById(produtoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (combo.getTipo() != Produto.Tipo.COMBO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Produto não é combo");
        }

        Map<String, Object> out = new HashMap<>();
        out.put("id", combo.getId());
        out.put("nome", combo.getNome());
        out.put("descricao", combo.getDescricao());
        out.put("preco", combo.getPreco());
        out.put("fotoUrl", combo.getFotoUrl());
        out.put("tipo", "COMBO");

        // Expandir filhos respeitando quantidade — pra frontend criar 1 bloco
        // de complementos por unidade (ex: 2x Açaí 500ml vira 2 blocos).
        List<Map<String, Object>> slots = new ArrayList<>();
        var filhos = comboItemRepo.findByComboIdOrderByOrdemAscIdAsc(combo.getId());
        for (var ci : filhos) {
            Produto filho = ci.getProdutoFilho();
            if (filho == null) continue;
            int qtd = ci.getQuantidade() != null && ci.getQuantidade() > 0 ? ci.getQuantidade() : 1;
            // Grupos do filho (lê só ativos, mesma serialização do cardápio normal)
            List<Map<String, Object>> grupos = grupoComplementoRepo
                    .findByProdutoIdOrderByIdAsc(filho.getId())
                    .stream().map(g -> serializarGrupoPublico(g)).toList();

            for (int n = 1; n <= qtd; n++) {
                Map<String, Object> slot = new HashMap<>();
                slot.put("comboItemId", ci.getId());
                slot.put("produtoFilhoId", filho.getId());
                slot.put("nomeFilho", filho.getNome());
                slot.put("indice", n);          // 1, 2, 3 — pro frontend mostrar "#1"
                slot.put("totalDoFilho", qtd);  // total de repetições
                slot.put("fotoUrl", filho.getFotoUrl());
                slot.put("grupos", grupos);
                slots.add(slot);
            }
        }
        out.put("slots", slots);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .body(out);
    }

    // ── helpers ──

    private Restaurante meuRestaurante(String email) {
        return restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private void salvarItensDoCombo(Produto combo, Restaurante r, Object itensRaw) {
        if (!(itensRaw instanceof List<?> ll)) return;
        int ordemAuto = 0;
        for (Object o : ll) {
            if (!(o instanceof Map<?,?> m)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> im = (Map<String, Object>) m;
            Long filhoId = longOrNull(im, "produtoFilhoId");
            if (filhoId == null) continue;

            Produto filho = produtoRepo.findById(filhoId).orElse(null);
            if (filho == null) {
                log.warn("[Combo] filho id={} não existe — pulando", filhoId);
                continue;
            }
            // Multi-tenant: filho tem que ser do mesmo restaurante
            if (filho.getRestaurante() == null
                    || !filho.getRestaurante().getId().equals(r.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Produto filho não pertence ao seu restaurante");
            }
            // Combo não pode conter outro combo (pra simplificar regra de preço)
            if (filho.getTipo() == Produto.Tipo.COMBO) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Combo não pode conter outro combo (" + filho.getNome() + ")");
            }
            // Combo não pode conter ele mesmo
            if (filho.getId().equals(combo.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Combo não pode conter ele mesmo");
            }

            int qtd = intOr(im, "quantidade", 1);
            if (qtd < 1) qtd = 1;
            if (qtd > 99) qtd = 99; // sanity

            ComboItem ci = ComboItem.builder()
                    .combo(combo)
                    .produtoFilho(filho)
                    .quantidade(qtd)
                    .ordem(intOr(im, "ordem", ordemAuto++))
                    .build();
            comboItemRepo.save(ci);
        }
    }

    private Map<String, Object> serializarCombo(Produto combo, boolean admin) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", combo.getId());
        out.put("nome", combo.getNome());
        out.put("descricao", combo.getDescricao());
        out.put("preco", combo.getPreco());
        out.put("fotoUrl", combo.getFotoUrl());
        out.put("disponivel", Boolean.TRUE.equals(combo.getDisponivel()));
        out.put("destaque", Boolean.TRUE.equals(combo.getDestaque()));
        out.put("ordem", combo.getOrdem());
        out.put("tipo", "COMBO");
        if (combo.getCategoria() != null) {
            out.put("categoriaId", combo.getCategoria().getId());
            out.put("categoriaNome", combo.getCategoria().getNome());
        }

        List<Map<String, Object>> itensOut = new ArrayList<>();
        for (var ci : comboItemRepo.findByComboIdOrderByOrdemAscIdAsc(combo.getId())) {
            Produto f = ci.getProdutoFilho();
            if (f == null) continue;
            Map<String, Object> mi = new HashMap<>();
            mi.put("id", ci.getId());
            mi.put("produtoFilhoId", f.getId());
            mi.put("nomeFilho", f.getNome());
            mi.put("fotoUrl", f.getFotoUrl());
            mi.put("quantidade", ci.getQuantidade());
            mi.put("ordem", ci.getOrdem());
            itensOut.add(mi);
        }
        out.put("itens", itensOut);
        return out;
    }

    /** Serialização pública dos grupos — só ativos. */
    private Map<String, Object> serializarGrupoPublico(ComplementoGrupo g) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", g.getId());
        out.put("nome", g.getNome());
        out.put("obrigatorio", Boolean.TRUE.equals(g.getObrigatorio()));
        out.put("minEscolhas", g.getMinEscolhas() != null ? g.getMinEscolhas() : 0);
        out.put("maxEscolhas", g.getMaxEscolhas() != null ? g.getMaxEscolhas() : 1);
        List<Map<String, Object>> itens = new ArrayList<>();
        if (g.getItens() != null) {
            for (var i : g.getItens()) {
                if (!Boolean.TRUE.equals(i.getAtivo())) continue;
                Map<String, Object> mi = new HashMap<>();
                mi.put("id", i.getId());
                mi.put("nome", i.getNome());
                mi.put("precoAdicional", i.getPrecoAdicional() != null
                        ? i.getPrecoAdicional() : BigDecimal.ZERO);
                itens.add(mi);
            }
        }
        out.put("itens", itens);
        return out;
    }

    // ── parsers ──

    private static String strReq(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, k + " é obrigatório");
        String s = v.toString().trim();
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, k + " não pode ser vazio");
        return s;
    }
    private static String strOr(Map<String, Object> m, String k, String d) {
        Object v = m.get(k);
        if (v == null) return d;
        String s = v.toString().trim();
        return s.isEmpty() ? d : s;
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
    private static Long longOrNull(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }
    private static BigDecimal decOr(Map<String, Object> m, String k, BigDecimal d) {
        Object v = m.get(k);
        if (v == null) return d;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return d; }
    }
}
