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
import com.mydelivery.model.ComboGrupo;
import com.mydelivery.model.GrupoComplementoModelo;
import com.mydelivery.repository.CategoriaRepository;
import com.mydelivery.repository.ComboGrupoRepository;
import com.mydelivery.repository.ComboItemRepository;
import com.mydelivery.repository.ComplementoGrupoRepository;
import com.mydelivery.repository.GrupoComplementoModeloRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final ComboGrupoRepository comboGrupoRepo;
    private final ComplementoGrupoRepository grupoComplementoRepo;
    private final GrupoComplementoModeloRepository grupoModeloRepo;
    private final CategoriaRepository categoriaRepo;
    private final RestauranteRepository restauranteRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── ADMIN: criar ──

    @PostMapping("/api/restaurante/combos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> criar(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        // DIAGNÓSTICO: logamos o body recebido (sem dados sensíveis) pra
        // facilitar achar o problema em prod quando o frontend reportar 400.
        log.info("[Combo:criar] email={} body.keys={} itens.size={}",
                email,
                body != null ? body.keySet() : null,
                body != null && body.get("itens") instanceof List<?> l ? l.size() : 0);

        Restaurante r = meuRestaurante(email);

        try {
            Produto combo = Produto.builder()
                    .restaurante(r)
                    .nome(strReq(body, "nome"))
                    .descricao(strOr(body, "descricao", null))
                    .preco(decOr(body, "preco", BigDecimal.ZERO))
                    .precoOriginal(decOr(body, "precoOriginal", null))
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
            log.info("[Combo:criar] produto combo salvo id={}", combo.getId());
            salvarItensDoCombo(combo, r, body.get("itens"));
            // NOVO: salva grupos selecionados pelo dono (item 2 do refactor)
            salvarGruposDoCombo(combo, r, body.get("gruposIds"));
            log.info("[Combo:criar] OK — combo id={} com {} itens e {} grupos",
                    combo.getId(),
                    comboItemRepo.findByComboIdOrderByOrdemAscIdAsc(combo.getId()).size(),
                    comboGrupoRepo.findByComboIdOrderByOrdemAscIdAsc(combo.getId()).size());

            return ResponseEntity.status(HttpStatus.CREATED).body(serializarCombo(combo, true));
        } catch (ResponseStatusException e) {
            log.warn("[Combo:criar] validação falhou: {} - {}", e.getStatusCode(), e.getReason());
            throw e;
        } catch (Exception e) {
            log.error("[Combo:criar] FALHA inesperada", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Erro ao salvar combo: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
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
        if (body.containsKey("precoOriginal")) combo.setPrecoOriginal(decOr(body, "precoOriginal", null));
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
        // Substituição completa dos grupos selecionados
        if (body.containsKey("gruposIds")) {
            comboGrupoRepo.deleteByComboId(combo.getId());
            salvarGruposDoCombo(combo, r, body.get("gruposIds"));
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
        comboGrupoRepo.deleteByComboId(combo.getId());
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
        out.put("precoOriginal", combo.getPrecoOriginal()); // pra mostrar "de R$ X / por R$ Y" no cliente
        out.put("fotoUrl", combo.getFotoUrl());
        out.put("tipo", "COMBO");

        // Grupos do combo escolhidos pelo dono. Cada grupo pode ter:
        //  - filhosAplicaveis: vazio = todos / lista = só esses produtos
        //  - preset (V3 combo fixo): se preenchido, marca itens pré-selecionados
        //    pelo restaurante. Cliente NÃO escolhe e o preço do combo NÃO soma.
        //    Frontend renderiza como "Inclui: ..." em vez de seletor.
        var gruposDoCombo = comboGrupoRepo.findByComboIdOrderByOrdemAscIdAsc(combo.getId());
        // Cada entrada: { "grupo" = Map serializado, "filhos" = List<Long>, "preset" = List<Map> }
        List<Map<String, Object>> gruposGlobaisComFiltro = null;
        if (!gruposDoCombo.isEmpty()) {
            gruposGlobaisComFiltro = new ArrayList<>();
            for (var cg : gruposDoCombo) {
                Map<String, Object> g = serializarGrupoModeloComoPublico(cg.getGrupoModelo());
                if (g != null) {
                    // Se tem preset, anexa pro frontend cliente renderizar como info
                    var preset = parsePresetItens(cg.getPresetItensJson());
                    if (!preset.isEmpty()) {
                        // Resolve cada {i,q} pra {nome, preco, q} usando itens do grupo modelo
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> itensDoGrupo = (List<Map<String, Object>>) g.get("itens");
                        List<Map<String, Object>> presetResolvido = new ArrayList<>();
                        for (var p : preset) {
                            int idx = (int) p.get("i");
                            int q = (int) p.get("q");
                            if (idx >= 0 && idx < itensDoGrupo.size()) {
                                var it = itensDoGrupo.get(idx);
                                Map<String, Object> pr = new HashMap<>();
                                pr.put("nome", it.get("nome"));
                                pr.put("precoAdicional", it.get("precoAdicional"));
                                pr.put("quantidade", q);
                                presetResolvido.add(pr);
                            }
                        }
                        g.put("preset", presetResolvido); // marca pro frontend
                    }
                    Map<String, Object> wrap = new HashMap<>();
                    wrap.put("grupo", g);
                    wrap.put("filhos", parseFilhosAplicaveis(cg.getFilhosAplicaveisJson()));
                    gruposGlobaisComFiltro.add(wrap);
                }
            }
        }

        // Expandir filhos respeitando quantidade — pra frontend criar 1 bloco
        // de complementos por unidade (ex: 2x Açaí 500ml vira 2 blocos).
        List<Map<String, Object>> slots = new ArrayList<>();
        var filhos = comboItemRepo.findByComboIdOrderByOrdemAscIdAsc(combo.getId());
        for (var ci : filhos) {
            Produto filho = ci.getProdutoFilho();
            if (filho == null) continue;
            int qtd = ci.getQuantidade() != null && ci.getQuantidade() > 0 ? ci.getQuantidade() : 1;
            // Decide qual fonte de grupos usar — grupos do combo (novo) ou
            // herdados do filho (retrocompat). Pro novo, filtra por filhosAplicaveis:
            // grupo só entra no slot se filhos==[] (todos) ou contém o filho.id.
            List<Map<String, Object>> grupos;
            if (gruposGlobaisComFiltro != null) {
                grupos = new ArrayList<>();
                for (var wrap : gruposGlobaisComFiltro) {
                    @SuppressWarnings("unchecked")
                    List<Long> filhosAplic = (List<Long>) wrap.get("filhos");
                    boolean aplicaEmTodos = filhosAplic == null || filhosAplic.isEmpty();
                    if (aplicaEmTodos || filhosAplic.contains(filho.getId())) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> g = (Map<String, Object>) wrap.get("grupo");
                        grupos.add(g);
                    }
                }
            } else {
                grupos = grupoComplementoRepo
                        .findByProdutoIdOrderByIdAsc(filho.getId())
                        .stream().map(g -> serializarGrupoPublico(g)).toList();
            }

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

    /**
     * Salva as ligações combo → grupos modelo escolhidos pelo dono.
     *
     * Aceita 2 formatos no body, retrocompat:
     *  1. Array de IDs (legado):
     *     gruposIds: [1, 2, 3]
     *     → todos os grupos aplicam em todos os filhos do combo.
     *  2. Array de objetos (novo, granular por produto):
     *     gruposIds: [
     *       { "id": 1, "filhosAplicaveis": null },          // aplica em todos
     *       { "id": 2, "filhosAplicaveis": [403, 402] },    // só nesses 2 filhos
     *       { "id": 3 }                                     // null = aplica em todos
     *     ]
     *
     * Multi-tenant: só aceita grupos do mesmo restaurante.
     */
    private void salvarGruposDoCombo(Produto combo, Restaurante r, Object gruposIdsRaw) {
        if (!(gruposIdsRaw instanceof List<?> ll) || ll.isEmpty()) return;
        int ordemAuto = 0;
        for (Object o : ll) {
            // Formato novo: objeto { id, filhosAplicaveis }
            Long gid = null;
            List<Long> filhosAplicaveis = null;
            if (o instanceof Map<?,?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> im = (Map<String, Object>) m;
                gid = longOrNull(im, "id");
                Object faRaw = im.get("filhosAplicaveis");
                if (faRaw instanceof List<?> fl && !fl.isEmpty()) {
                    filhosAplicaveis = new java.util.ArrayList<>();
                    for (Object x : fl) {
                        if (x instanceof Number n) filhosAplicaveis.add(n.longValue());
                        else try { filhosAplicaveis.add(Long.parseLong(x.toString())); }
                             catch (Exception ignore) {}
                    }
                }
            } else {
                // Formato legado: ID solto
                gid = longOrNull(java.util.Map.of("id", o), "id");
            }
            if (gid == null) continue;
            GrupoComplementoModelo gm = grupoModeloRepo.findById(gid).orElse(null);
            if (gm == null) {
                log.warn("[Combo:grupos] modelo id={} não existe — pulando", gid);
                continue;
            }
            if (gm.getRestaurante() == null
                    || !gm.getRestaurante().getId().equals(r.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Grupo modelo não pertence ao seu restaurante");
            }
            // Serializa lista pra JSON simples (sem Jackson — formato é trivial)
            String faJson = null;
            if (filhosAplicaveis != null && !filhosAplicaveis.isEmpty()) {
                faJson = "[" + filhosAplicaveis.stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(","))
                        + "]";
            }

            // Preset de itens (combo fixo) — só extrai se veio no formato objeto
            String presetJson = null;
            if (o instanceof Map<?,?> m2) {
                @SuppressWarnings("unchecked")
                Map<String, Object> im2 = (Map<String, Object>) m2;
                Object presetRaw = im2.get("preset");
                if (presetRaw instanceof List<?> pl && !pl.isEmpty()) {
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    for (Object pe : pl) {
                        if (!(pe instanceof Map<?,?> pm)) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> pim = (Map<String, Object>) pm;
                        Integer i = intOrNull(pim, "i");
                        Integer q = intOrNull(pim, "q");
                        if (i == null || q == null || i < 0 || q <= 0) continue;
                        if (!first) sb.append(",");
                        sb.append("{\"i\":").append(i).append(",\"q\":").append(q).append("}");
                        first = false;
                    }
                    sb.append("]");
                    if (sb.length() > 2) presetJson = sb.toString();
                }
            }

            ComboGrupo cg = ComboGrupo.builder()
                    .combo(combo)
                    .grupoModelo(gm)
                    .ordem(ordemAuto++)
                    .filhosAplicaveisJson(faJson)
                    .presetItensJson(presetJson)
                    .build();
            comboGrupoRepo.save(cg);
        }
    }

    /** intOrNull tolerante a Number ou String. Devolve null se não conseguir. */
    private Integer intOrNull(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (Exception e) { return null; }
    }

    /** Parse o preset JSON ([{"i":0,"q":1},...]) em List de Map.
     *  Formato simples, parser manual. */
    private List<Map<String, Object>> parsePresetItens(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        // Match { "i":N , "q":N } — simples regex porque o formato é fechado
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{\\s*\"i\"\\s*:\\s*(\\d+)\\s*,\\s*\"q\"\\s*:\\s*(\\d+)\\s*\\}")
                .matcher(json);
        while (m.find()) {
            Map<String, Object> ent = new HashMap<>();
            ent.put("i", Integer.parseInt(m.group(1)));
            ent.put("q", Integer.parseInt(m.group(2)));
            out.add(ent);
        }
        return out;
    }

    /** Parse o JSON simples salvo em filhosAplicaveisJson → List&lt;Long&gt;.
     *  Retorna lista vazia se null/inválido. */
    private List<Long> parseFilhosAplicaveis(String json) {
        if (json == null || json.isBlank()) return List.of();
        String s = json.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        if (s.isBlank()) return List.of();
        List<Long> out = new java.util.ArrayList<>();
        for (String tok : s.split(",")) {
            try { out.add(Long.parseLong(tok.trim())); } catch (Exception ignore) {}
        }
        return out;
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
            // Combo não pode conter ele mesmo (loop infinito ao expandir)
            if (filho.getId().equals(combo.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Combo não pode conter ele mesmo");
            }
            // Combo dentro de combo agora é PERMITIDO. Ao expandir no cardápio
            // o cliente vai ver o combo-filho como item único (não expande
            // recursivamente) — pra simplificar a regra de preço, o preço
            // do combo-filho não soma; o que vale é o preço tabelado do
            // combo-pai. Se virar problema, restringir depois.

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
        out.put("precoOriginal", combo.getPrecoOriginal());
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

        // Grupos selecionados pelo dono + granularidade por filho (V2 refactor).
        // filhosAplicaveis = [] → aplica em todos. lista de IDs → só nesses filhos.
        List<Map<String, Object>> gruposOut = new ArrayList<>();
        for (var cg : comboGrupoRepo.findByComboIdOrderByOrdemAscIdAsc(combo.getId())) {
            var gm = cg.getGrupoModelo();
            if (gm == null) continue;
            Map<String, Object> g = new HashMap<>();
            g.put("id", gm.getId());
            g.put("nome", gm.getNome());
            g.put("ordem", cg.getOrdem());
            g.put("filhosAplicaveis", parseFilhosAplicaveis(cg.getFilhosAplicaveisJson()));
            g.put("preset", parsePresetItens(cg.getPresetItensJson()));
            gruposOut.add(g);
        }
        out.put("grupos", gruposOut);
        out.put("gruposIds", gruposOut.stream().map(g -> g.get("id")).toList());

        return out;
    }

    /**
     * Converte um GrupoComplementoModelo (template salvo da Fase 1) no mesmo
     * formato JSON que o frontend público espera ({id, nome, obrigatorio,
     * minEscolhas, maxEscolhas, itens:[{id, nome, precoAdicional}]}).
     *
     * O modelo guarda os itens como JSON em coluna TEXT (formato:
     * [{nome, precoAdicional}, ...]). Aqui fazemos parse e atribuímos IDs
     * derivados (gid:idx) pra ficar único no DOM do frontend.
     */
    private Map<String, Object> serializarGrupoModeloComoPublico(GrupoComplementoModelo gm) {
        if (gm == null) return null;
        Map<String, Object> out = new HashMap<>();
        out.put("id", gm.getId());
        out.put("nome", gm.getNome());
        out.put("obrigatorio", Boolean.TRUE.equals(gm.getObrigatorio()));
        out.put("minEscolhas", gm.getMinEscolhas() != null ? gm.getMinEscolhas() : 0);
        out.put("maxEscolhas", gm.getMaxEscolhas() != null ? gm.getMaxEscolhas() : 1);
        List<Map<String, Object>> itens = new ArrayList<>();
        if (gm.getItensJson() != null && !gm.getItensJson().isBlank()) {
            try {
                List<Map<String, Object>> raw = objectMapper.readValue(
                        gm.getItensJson(),
                        new TypeReference<List<Map<String, Object>>>() {});
                int idx = 0;
                for (Map<String, Object> it : raw) {
                    if (it == null || it.get("nome") == null) continue;
                    Map<String, Object> mi = new HashMap<>();
                    mi.put("id", gm.getId() * 1000 + (idx++)); // ID sintético único
                    mi.put("nome", it.get("nome").toString());
                    BigDecimal preco = BigDecimal.ZERO;
                    Object pr = it.get("precoAdicional");
                    if (pr != null) {
                        try { preco = new BigDecimal(pr.toString()); } catch (Exception ignore) {}
                    }
                    mi.put("precoAdicional", preco);
                    itens.add(mi);
                }
            } catch (Exception e) {
                log.warn("[Combo] itens_json corrompido grupo modelo id={}: {}", gm.getId(), e.getMessage());
            }
        }
        out.put("itens", itens);
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
