package com.mydelivery.controller;

import java.time.LocalDate;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.dto.relatorio.RelatorioFinanceiroDTO;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.RelatorioFinanceiroService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class RelatorioController {

    private final RelatorioFinanceiroService relatorioService;
    private final RestauranteRepository restauranteRepository;

    /**
     * Relatório financeiro completo do período.
     * Query: ?dataInicio=2026-05-01&dataFim=2026-05-31
     * Default: últimos 30 dias.
     */
    @GetMapping("/api/restaurante/relatorios/financeiro")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<RelatorioFinanceiroDTO> financeiro(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String dataInicio,
            @RequestParam(required = false) String dataFim) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        LocalDate ini = dataInicio != null && !dataInicio.isBlank()
                ? LocalDate.parse(dataInicio)
                : LocalDate.now().minusDays(29);
        LocalDate fim = dataFim != null && !dataFim.isBlank()
                ? LocalDate.parse(dataFim)
                : LocalDate.now();
        return ResponseEntity.ok(relatorioService.gerar(r.getId(), ini, fim));
    }
}
