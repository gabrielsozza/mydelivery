package com.mydelivery.controller;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mydelivery.model.GrupoComplementoModelo;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.GrupoComplementoModeloRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Templates de grupos de complementos salvos no banco — substitui o
 * storage que antes ficava no {@code localStorage} do browser.
 *
 * Motivação: os "grupos salvos" sumiam ao trocar de navegador, limpar
 * cache ou abrir em outro device. Agora persistem por restaurante e
 * podem ser reaplicados em vários produtos.
 *
 * Endpoints (auth RESTAURANTE):
 *   GET    /api/restaurante/grupos-modelo            → lista
 *   POST   /api/restaurante/grupos-modelo            → cria ou substitui (upsert por nome)
 *   POST   /api/restaurante/grupos-modelo/sync       → bulk upsert (migração inicial do localStorage)
 *   DELETE /api/restaurante/grupos-modelo/{id}       → remove
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GrupoComplementoModeloController {

    private final GrupoComplementoModeloRepository repo;
    private final RestauranteRepository restauranteRepo;
    private final ObjectMapper json = new ObjectMapper();

    // ── LISTAR ──

    @GetMapping("/api/restaurante/grupos-modelo")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> listar(
            @AuthenticationPrincipal String email) {
        Restaurante r = meuRestaurante(email);
        var lista = repo.findByRestauranteIdOrderByNomeAsc(r.getId())
                .stream().map(this::serializar).toList();
        return ResponseEntity.ok(lista);
    }

    // ── UPSERT (substitui se já existe um com mesmo nome normalizado) ──

    @PostMapping("/api/restaurante/grupos-modelo")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> salvar(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        Restaurante r = meuRestaurante(email);
        Map<String, Object> salvo = upsertGrupo(r, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(salvo);
    }

    // ── BULK UPSERT (1ª migração do localStorage) ──

    /**
     * Recebe array de grupos do localStorage e faz upsert em lote.
     * Idempotente: pode chamar 2x sem duplicar. Resposta inclui a lista
     * canônica atualizada pro frontend substituir o cache local.
     */
    @PostMapping("/api/restaurante/grupos-modelo/sync")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Map<String, Object>> sync(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        Restaurante r = meuRestaurante(email);
        Object grupos = body.get("grupos");
        int total = 0;
        if (grupos instanceof List<?> ll) {
            for (Object o : ll) {
                if (!(o instanceof Map<?,?> m)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) m;
                try {
                    upsertGrupo(r, mm);
                    total++;
                } catch (Exception e) {
                    log.warn("[GrupoModelo:sync] falha em 1 grupo (segue): {}", e.getMessage());
                }
            }
        }
        var lista = repo.findByRestauranteIdOrderByNomeAsc(r.getId())
                .stream().map(this::serializar).toList();
        Map<String, Object> resp = new HashMap<>();
        resp.put("importados", total);
        resp.put("grupos", lista);
        return ResponseEntity.ok(resp);
    }

    // ── DELETE ──

    @DeleteMapping("/api/restaurante/grupos-modelo/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Void> deletar(
            @AuthenticationPrincipal String email,
            @PathVariable Long id) {
        Restaurante r = meuRestaurante(email);
        GrupoComplementoModelo g = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!g.getRestaurante().getId().equals(r.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        repo.delete(g);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ──

    private Restaurante meuRestaurante(String email) {
        return restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    /** Salva ou substitui (upsert) grupo modelo a partir do body. */
    private Map<String, Object> upsertGrupo(Restaurante r, Map<String, Object> body) {
        String nome = strReq(body, "nome");
        String norm = GrupoComplementoModelo.normalizar(nome);

        GrupoComplementoModelo g = repo
                .findByRestauranteIdAndNomeNormalizado(r.getId(), norm)
                .orElseGet(() -> GrupoComplementoModelo.builder()
                        .restaurante(r)
                        .build());

        g.setNome(nome);
        g.setNomeNormalizado(norm);
        g.setObrigatorio(boolOr(body, "obrigatorio", false));
        g.setMinEscolhas(intOr(body, "minEscolhas", 0));
        g.setMaxEscolhas(intOr(body, "maxEscolhas", 1));

        Object itens = body.get("itens");
        try {
            g.setItensJson(json.writeValueAsString(itens != null ? itens : List.of()));
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falha serializando itens: " + e.getMessage());
        }

        g = repo.saveAndFlush(g);
        return serializar(g);
    }

    private Map<String, Object> serializar(GrupoComplementoModelo g) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", g.getId());
        out.put("nome", g.getNome());
        out.put("obrigatorio", Boolean.TRUE.equals(g.getObrigatorio()));
        out.put("minEscolhas", g.getMinEscolhas() != null ? g.getMinEscolhas() : 0);
        out.put("maxEscolhas", g.getMaxEscolhas() != null ? g.getMaxEscolhas() : 1);
        // Itens: parse JSON pra array — frontend já consome esse formato
        List<?> itens = List.of();
        if (g.getItensJson() != null && !g.getItensJson().isBlank()) {
            try {
                itens = json.readValue(g.getItensJson(), List.class);
            } catch (Exception e) {
                log.warn("[GrupoModelo] itens_json corrompido id={}: {}", g.getId(), e.getMessage());
            }
        }
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
}
