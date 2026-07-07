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
        // Admin: inclui inativos (precisa ver pra reativar).
        return ResponseEntity.ok(grupoRepo.findByProdutoIdOrderByIdAsc(p.getId())
                .stream().map(g -> serializar(g, true)).toList());
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
                .modoPreco(parseModoPreco(body.get("modoPreco")))
                .itens(new java.util.ArrayList<>())  // garante coleção mutável
                .build();
        // Adiciona itens ANTES de salvar — cascade.ALL persiste tudo numa só
        adicionarItensA(g, body.get("itens"));
        g = grupoRepo.saveAndFlush(g);
        return ResponseEntity.status(HttpStatus.CREATED).body(serializar(g, true));
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
        if (body.containsKey("modoPreco"))    g.setModoPreco(parseModoPreco(body.get("modoPreco")));

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
        return ResponseEntity.ok(serializar(g, true));
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
                    .descricao(strOr(imap, "descricao", null))
                    .precoAdicional(decOr(imap, "precoAdicional", BigDecimal.ZERO))
                    .maxSelecoes(intOrNull(imap, "maxSelecoes"))
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

    /**
     * Toggle ativar/desativar item de complemento, com propagação automática
     * para TODOS os itens de mesmo nome no restaurante inteiro.
     *
     * Caso de uso: o dono cadastra "Morango" como complemento em "Açaí 300ml"
     * E em "Açaí 500ml". Quando acaba o morango no estoque, ele desativa em
     * UM produto e o sistema desativa nos outros automaticamente — assim o
     * morango some do cardápio do cliente em todos os produtos onde aparece.
     *
     * Match case-insensitive por nome (após trim) + filtro pelo restaurante
     * do email logado. Items de outros restaurantes nunca são afetados.
     *
     * Body: { "ativo": true|false, "propagar": true (default) }
     * Se propagar=false, atualiza só o item especificado.
     */
    @org.springframework.web.bind.annotation.PatchMapping("/api/complementos/itens/{itemId}/ativo")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> toggleAtivoItem(
            @AuthenticationPrincipal String email,
            @PathVariable Long itemId,
            @RequestBody Map<String, Object> body) {
        ComplementoItem alvo = itemRepo.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item não encontrado"));
        // Multi-tenant guard: item tem que pertencer a um produto do dono.
        Produto produtoDoAlvo = alvo.getGrupo() != null ? alvo.getGrupo().getProduto() : null;
        if (produtoDoAlvo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        checkOwner(email, produtoDoAlvo.getId());

        boolean ativo = boolOr(body, "ativo", true);
        boolean propagar = boolOr(body, "propagar", true);

        int afetados = 0;
        if (!propagar) {
            alvo.setAtivo(ativo);
            itemRepo.save(alvo);
            afetados = 1;
        } else {
            // Busca todos os itens com mesmo nome do mesmo restaurante.
            Long restauranteId = produtoDoAlvo.getRestaurante().getId();
            String alvoNomeNorm = normalizarNomeItem(alvo.getNome());
            // Pra evitar query custosa por nome, fazemos varredura em memória
            // sobre todos os grupos dos produtos do restaurante. Em volumes
            // típicos (algumas dezenas de produtos × poucos grupos × poucos
            // itens), são <500 entidades — performance OK.
            var produtos = produtoRepo.findByRestauranteId(restauranteId);
            for (var prod : produtos) {
                var grupos = grupoRepo.findByProdutoIdOrderByIdAsc(prod.getId());
                for (var grp : grupos) {
                    if (grp.getItens() == null) continue;
                    for (var it : grp.getItens()) {
                        if (alvoNomeNorm.equals(normalizarNomeItem(it.getNome()))) {
                            it.setAtivo(ativo);
                            itemRepo.save(it);
                            afetados++;
                        }
                    }
                }
            }
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("ativo", ativo);
        resp.put("nome", alvo.getNome());
        resp.put("afetados", afetados);
        return ResponseEntity.ok(resp);
    }

    private static String normalizarNomeItem(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase();
    }

    // ── PÚBLICO (cardápio) ──

    @GetMapping("/public/produtos/{produtoId}/complementos")
    public ResponseEntity<List<Map<String, Object>>> listarPublico(@PathVariable Long produtoId) {
        // Público: cliente final só vê itens ATIVOS. Itens desativados pelo
        // dono (ex.: ingrediente em falta) somem completamente do cardápio.
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .body(grupoRepo.findByProdutoIdOrderByIdAsc(produtoId)
                        .stream().map(g -> serializar(g, false)).toList());
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

    /**
     * @param incluirInativos true = devolve TODOS os itens (uso admin, pra
     *                        ver e poder reativar); false = filtra só ativos
     *                        (cardápio público — cliente não vê o que falta).
     */
    private Map<String, Object> serializar(ComplementoGrupo g, boolean incluirInativos) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", g.getId());
        out.put("nome", g.getNome());
        out.put("obrigatorio", Boolean.TRUE.equals(g.getObrigatorio()));
        out.put("minEscolhas", g.getMinEscolhas() != null ? g.getMinEscolhas() : 0);
        out.put("maxEscolhas", g.getMaxEscolhas() != null ? g.getMaxEscolhas() : 1);
        out.put("modoPreco", g.getModoPreco() != null
                ? g.getModoPreco().name() : ComplementoGrupo.ModoPreco.SOMA.name());
        List<Map<String, Object>> itens = g.getItens() == null ? List.of()
                : g.getItens().stream()
                    .filter(i -> incluirInativos || Boolean.TRUE.equals(i.getAtivo()))
                    .map(i -> {
                        Map<String, Object> mi = new HashMap<>();
                        mi.put("id", i.getId());
                        mi.put("nome", i.getNome());
                        mi.put("descricao", i.getDescricao()); // pode ser null — front trata
                        mi.put("precoAdicional", i.getPrecoAdicional() != null ? i.getPrecoAdicional() : BigDecimal.ZERO);
                        mi.put("maxSelecoes", i.getMaxSelecoes()); // null = sem limite individual
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
    /** String opcional — retorna default (pode ser null). Vazio = null. */
    private static String strOr(Map<String, Object> m, String k, String d) {
        Object v = m.get(k);
        if (v == null) return d;
        String s = v.toString().trim();
        return s.isEmpty() ? d : s;
    }
    /** Integer opcional puro — null quando ausente ou inválido. */
    private static Integer intOrNull(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try { int n = Integer.parseInt(s); return n > 0 ? n : null; } catch (Exception e) { return null; }
    }

    /**
     * Parse tolerante do modoPreco vindo do front. Aceita "SOMA"/"MAIOR" (qualquer
     * casing) e cai em SOMA (default retrocompat) se ausente, vazio ou inválido.
     */
    private static ComplementoGrupo.ModoPreco parseModoPreco(Object v) {
        if (v == null) return ComplementoGrupo.ModoPreco.SOMA;
        String s = v.toString().trim().toUpperCase();
        if (s.isEmpty()) return ComplementoGrupo.ModoPreco.SOMA;
        try { return ComplementoGrupo.ModoPreco.valueOf(s); }
        catch (IllegalArgumentException e) { return ComplementoGrupo.ModoPreco.SOMA; }
    }
}
