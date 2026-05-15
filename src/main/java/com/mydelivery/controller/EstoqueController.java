package com.mydelivery.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.dto.estoque.InsumoDTO;
import com.mydelivery.dto.estoque.MovimentacaoDTO;
import com.mydelivery.dto.estoque.ViabilidadeDTO;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.EstoqueService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class EstoqueController {

    private final EstoqueService estoqueService;
    private final RestauranteRepository restauranteRepository;

    // ── INSUMOS ────────────────────────────────────────────────────────────

    @GetMapping("/api/restaurante/insumos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<InsumoDTO>> listar(@AuthenticationPrincipal String email) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(estoqueService.listarPorRestaurante(r.getId()));
    }

    @PostMapping("/api/restaurante/insumos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<InsumoDTO> criar(@AuthenticationPrincipal String email,
                                           @RequestBody InsumoDTO dto) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(estoqueService.criar(r.getId(), dto));
    }

    @PutMapping("/api/restaurante/insumos/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<InsumoDTO> atualizar(@AuthenticationPrincipal String email,
                                               @PathVariable Long id,
                                               @RequestBody InsumoDTO dto) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(estoqueService.atualizar(r.getId(), id, dto));
    }

    @DeleteMapping("/api/restaurante/insumos/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Void> deletar(@AuthenticationPrincipal String email,
                                        @PathVariable Long id) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        estoqueService.deletar(r.getId(), id);
        return ResponseEntity.noContent().build();
    }

    // ── MOVIMENTAÇÕES (ajustes manuais + histórico) ────────────────────────

    /**
     * Body: { "quantidade": 5.0, "observacao": "Recebi nova caixa" }
     * Quantidade POSITIVA = entrada; NEGATIVA = saída.
     */
    @PostMapping("/api/restaurante/insumos/{id}/movimentacao")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<MovimentacaoDTO> registrarMovimentacao(
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        BigDecimal qtd = body.get("quantidade") != null
                ? new BigDecimal(body.get("quantidade").toString())
                : null;
        String obs = body.get("observacao") != null ? body.get("observacao").toString() : null;
        return ResponseEntity.ok(estoqueService.registrarAjuste(r.getId(), id, qtd, obs));
    }

    /**
     * Registra uma PERDA (descarte) com motivo categorizado.
     * Body: { "quantidade": 1.5, "motivo": "VENCIMENTO", "observacao": "Caixa estragada" }
     */
    @PostMapping("/api/restaurante/insumos/{id}/perda")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<MovimentacaoDTO> registrarPerda(
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        BigDecimal qtd = body.get("quantidade") != null
                ? new BigDecimal(body.get("quantidade").toString())
                : null;
        String motivoStr = body.get("motivo") != null ? body.get("motivo").toString() : "OUTRO";
        com.mydelivery.model.MovimentacaoEstoque.MotivoPerda motivo;
        try {
            motivo = com.mydelivery.model.MovimentacaoEstoque.MotivoPerda.valueOf(motivoStr);
        } catch (Exception e) {
            motivo = com.mydelivery.model.MovimentacaoEstoque.MotivoPerda.OUTRO;
        }
        String obs = body.get("observacao") != null ? body.get("observacao").toString() : null;
        return ResponseEntity.ok(estoqueService.registrarPerda(r.getId(), id, qtd, motivo, obs));
    }

    @GetMapping("/api/restaurante/movimentacoes")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<MovimentacaoDTO>> historicoGeral(
            @AuthenticationPrincipal String email) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(estoqueService.listarMovimentacoes(r.getId()));
    }

    @GetMapping("/api/restaurante/insumos/{id}/movimentacoes")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<MovimentacaoDTO>> historicoDoInsumo(
            @AuthenticationPrincipal String email, @PathVariable Long id) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(estoqueService.listarMovimentacoesDoInsumo(r.getId(), id));
    }

    // ── VIABILIDADE (a inovação) ───────────────────────────────────────────

    @GetMapping("/api/restaurante/estoque/viabilidade")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<ViabilidadeDTO.Resumo> viabilidade(@AuthenticationPrincipal String email) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(estoqueService.calcularViabilidade(r.getId()));
    }

    /**
     * Relatório agregado por período.
     * Query params: ?dataInicio=2026-05-01&dataFim=2026-05-31
     * Default: últimos 30 dias.
     */
    @GetMapping("/api/restaurante/estoque/relatorio")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<com.mydelivery.dto.estoque.RelatorioEstoqueDTO> relatorio(
            @AuthenticationPrincipal String email,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String dataInicio,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String dataFim) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        java.time.LocalDateTime ini = dataInicio != null && !dataInicio.isBlank()
                ? java.time.LocalDate.parse(dataInicio).atStartOfDay()
                : java.time.LocalDate.now().minusDays(30).atStartOfDay();
        java.time.LocalDateTime fim = dataFim != null && !dataFim.isBlank()
                ? java.time.LocalDate.parse(dataFim).atTime(23, 59, 59)
                : java.time.LocalDateTime.now();
        return ResponseEntity.ok(estoqueService.relatorio(r.getId(), ini, fim));
    }
}
