package com.mydelivery.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.Caixa;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.CaixaService;

import lombok.RequiredArgsConstructor;

/**
 * Controller do módulo Caixa. Todos os endpoints exigem ROLE_RESTAURANTE.
 *
 * <p>Rotas:
 * <pre>
 *   GET   /api/restaurante/caixa/status               → status atual (aberto/fechado + resumo)
 *   POST  /api/restaurante/caixa/abrir                → abre novo com valor inicial
 *   POST  /api/restaurante/caixa/sangria              → retirada de dinheiro
 *   POST  /api/restaurante/caixa/suprimento           → entrada de dinheiro
 *   GET   /api/restaurante/caixa/resumo               → resumo do caixa aberto (pré-fechamento)
 *   POST  /api/restaurante/caixa/fechar               → fecha com valor encontrado
 *   GET   /api/restaurante/caixa/historico            → lista fechados últimos N dias
 *   GET   /api/restaurante/caixa/{id}/movimentacoes   → detalhes de um caixa
 * </pre>
 */
@RestController
@RequiredArgsConstructor
public class CaixaController {

    private final CaixaService caixaService;
    private final RestauranteRepository restauranteRepo;

    /** Retorna se tem caixa aberto + resumo atual. Front usa pra decidir
     *  qual tela mostrar (Abrir vs Movimentar). */
    @GetMapping("/api/restaurante/caixa/status")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> status(@AuthenticationPrincipal String email) {
        Restaurante r = getRestaurante(email);
        Optional<Caixa> aberto = caixaService.caixaAberto(r.getId());
        if (aberto.isEmpty()) {
            return ResponseEntity.ok(Map.of("aberto", false));
        }
        Map<String, Object> resumo = caixaService.resumoFechamento(aberto.get().getId());
        // envelopa pra tornar explícito o campo "aberto" na resposta
        java.util.LinkedHashMap<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("aberto", true);
        resp.putAll(resumo);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/restaurante/caixa/abrir")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> abrir(@AuthenticationPrincipal String email,
                                                        @RequestBody Map<String, Object> body) {
        Restaurante r = getRestaurante(email);
        BigDecimal valorInicial = decOf(body.get("valorInicial"));
        String operadorNome = strOf(body.get("operadorNome"));
        if (operadorNome == null || operadorNome.isBlank()) operadorNome = email;
        Caixa c = caixaService.abrir(r, email, operadorNome, valorInicial);
        return ResponseEntity.ok(Map.of("caixaId", c.getId(), "valorInicial", c.getValorInicial()));
    }

    @PostMapping("/api/restaurante/caixa/sangria")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> sangria(@AuthenticationPrincipal String email,
                                                        @RequestBody Map<String, Object> body) {
        Long caixaId = longOf(body.get("caixaId"));
        BigDecimal valor = decOf(body.get("valor"));
        String descricao = strOf(body.get("descricao"));
        var m = caixaService.registrarSangria(caixaId, valor, descricao, email);
        return ResponseEntity.ok(Map.of("id", m.getId(), "tipo", m.getTipo().name(), "valor", m.getValor()));
    }

    @PostMapping("/api/restaurante/caixa/suprimento")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> suprimento(@AuthenticationPrincipal String email,
                                                            @RequestBody Map<String, Object> body) {
        Long caixaId = longOf(body.get("caixaId"));
        BigDecimal valor = decOf(body.get("valor"));
        String descricao = strOf(body.get("descricao"));
        var m = caixaService.registrarSuprimento(caixaId, valor, descricao, email);
        return ResponseEntity.ok(Map.of("id", m.getId(), "tipo", m.getTipo().name(), "valor", m.getValor()));
    }

    @GetMapping("/api/restaurante/caixa/resumo")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> resumo(@AuthenticationPrincipal String email) {
        Restaurante r = getRestaurante(email);
        Caixa c = caixaService.caixaAberto(r.getId())
                .orElseThrow(() -> new RuntimeException("Nenhum caixa aberto"));
        return ResponseEntity.ok(caixaService.resumoFechamento(c.getId()));
    }

    @PostMapping("/api/restaurante/caixa/fechar")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> fechar(@AuthenticationPrincipal String email,
                                                        @RequestBody Map<String, Object> body) {
        Long caixaId = longOf(body.get("caixaId"));
        BigDecimal encontrado = decOf(body.get("valorEncontrado"));
        String observacao = strOf(body.get("observacao"));
        return ResponseEntity.ok(caixaService.fechar(caixaId, encontrado, observacao));
    }

    @GetMapping("/api/restaurante/caixa/historico")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> historico(@AuthenticationPrincipal String email,
                                                                @RequestParam(defaultValue = "30") int dias) {
        Restaurante r = getRestaurante(email);
        return ResponseEntity.ok(caixaService.historico(r.getId(), dias));
    }

    @GetMapping("/api/restaurante/caixa/{id}/movimentacoes")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> movimentacoes(@AuthenticationPrincipal String email,
                                                                    @PathVariable Long id) {
        return ResponseEntity.ok(caixaService.movimentacoes(id));
    }

    // ── Helpers de parsing do body Map<String,Object> ────────────────────

    private Restaurante getRestaurante(String email) {
        return restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
    }

    private static String strOf(Object o) {
        return o == null ? null : o.toString();
    }
    private static Long longOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }
    private static BigDecimal decOf(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return null; }
    }
}
