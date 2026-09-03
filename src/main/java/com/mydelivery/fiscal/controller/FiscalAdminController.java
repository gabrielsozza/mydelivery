package com.mydelivery.fiscal.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.fiscal.model.NotaFiscalEmitida;
import com.mydelivery.fiscal.repository.NotaFiscalEmitidaRepository;
import com.mydelivery.fiscal.service.NfceEmissorService;
import com.mydelivery.fiscal.service.NfceStorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Endpoints internos do admin — visibilidade global das notas fiscais de
 * TODAS as lojas (o {@link FiscalController} do dono só mostra as dele).
 *
 * <p>Segurança: header {@code X-Admin-Secret} obrigatório em todos —
 * mesmo padrão dos outros endpoints admin-internal.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin-internal/fiscal")
@RequiredArgsConstructor
public class FiscalAdminController {

    private final NotaFiscalEmitidaRepository notaRepo;
    private final NfceEmissorService emissor;
    private final NfceStorageService storage;

    @Value("${mydelivery.admin.internal-secret:}")
    private String esperado;

    /**
     * Log de boot — confirma se a env {@code ADMIN_INTERNAL_SECRET} foi lida.
     * Se aparecer {@code esperado=0chars} = env vazia (ninguém do admin panel
     * consegue chamar). Se aparecer {@code esperado=NNchars} = OK, e se admin
     * envia mesma qtd de chars, deveria bater.
     */
    @jakarta.annotation.PostConstruct
    public void logBoot() {
        log.info("[FiscalAdmin][MainApi] boot: esperado={}chars",
                esperado == null ? 0 : esperado.length());
    }

    // ═════ NOTAS (com filtros) ══════════════════════════════════════════
    /**
     * Lista notas com filtros. Params:
     *  - status: PENDENTE|AUTORIZADA|REJEITADA|CANCELADA|CONTINGENCIA_EPEC (opcional)
     *  - restauranteId: filtra por 1 loja (opcional)
     *  - ambiente: 1 (produção) ou 2 (homologação) (opcional)
     *  - dias: 1|7|30|90 — janela desde N dias atrás (default 30)
     *  - limite: cap de linhas retornadas (default 500, max 2000)
     */
    @GetMapping("/notas")
    public ResponseEntity<Map<String, Object>> listarNotas(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long restauranteId,
            @RequestParam(required = false) Integer ambiente,
            @RequestParam(defaultValue = "30") int dias,
            @RequestParam(defaultValue = "500") int limite) {
        if (!validar(secret)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("erro", "Secret inválido"));
        int cap = Math.min(Math.max(limite, 1), 2000);
        LocalDateTime desde = LocalDateTime.now().minusDays(Math.max(1, dias));

        var todas = notaRepo.findAll();   // sem query customizada — filtramos in-memory (cap 2000)
        List<Map<String, Object>> out = new ArrayList<>();
        for (NotaFiscalEmitida n : todas) {
            if (n.getCriadoEm() != null && n.getCriadoEm().isBefore(desde)) continue;
            if (status != null && !status.isBlank() && !n.getStatus().name().equalsIgnoreCase(status)) continue;
            if (restauranteId != null && (n.getRestaurante() == null
                    || !restauranteId.equals(n.getRestaurante().getId()))) continue;
            if (ambiente != null && !ambiente.equals(n.getAmbiente())) continue;
            out.add(serializar(n));
            if (out.size() >= cap) break;
        }
        return ResponseEntity.ok(Map.of("total", out.size(), "notas", out));
    }

    // ═════ STATS (contadores globais) ═══════════════════════════════════
    /**
     * Contadores agregados nas últimas 24h + 7d. Usado no dashboard admin
     * pra visão rápida da saúde fiscal (quantas rejeitaram, quantas em
     * contingência, etc.).
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        if (!validar(secret)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("erro", "Secret inválido"));

        LocalDateTime agora = LocalDateTime.now();
        LocalDateTime desde24h = agora.minusHours(24);
        LocalDateTime desde7d = agora.minusDays(7);

        Map<String, Integer> ult24h = new LinkedHashMap<>();
        Map<String, Integer> ult7d = new LinkedHashMap<>();
        for (var s : NotaFiscalEmitida.Status.values()) { ult24h.put(s.name(), 0); ult7d.put(s.name(), 0); }
        int totalGeral = 0;
        int lojasComRejeicao = 0;
        java.util.Set<Long> lojasRej = new java.util.HashSet<>();

        for (NotaFiscalEmitida n : notaRepo.findAll()) {
            totalGeral++;
            if (n.getCriadoEm() == null) continue;
            String st = n.getStatus().name();
            if (n.getCriadoEm().isAfter(desde7d))  ult7d.merge(st, 1, Integer::sum);
            if (n.getCriadoEm().isAfter(desde24h)) ult24h.merge(st, 1, Integer::sum);
            if (n.getStatus() == NotaFiscalEmitida.Status.REJEITADA
                    && n.getRestaurante() != null
                    && n.getCriadoEm().isAfter(desde7d)) {
                lojasRej.add(n.getRestaurante().getId());
            }
        }
        lojasComRejeicao = lojasRej.size();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalHistorico", totalGeral);
        out.put("ult24h", ult24h);
        out.put("ult7d", ult7d);
        out.put("lojasComRejeicao7d", lojasComRejeicao);
        out.put("storageBackend", storage.backendAtivo());
        return ResponseEntity.ok(out);
    }

    // ═════ RETRY manual de nota específica ══════════════════════════════
    @PostMapping("/notas/{id}/retry")
    public ResponseEntity<Map<String, Object>> retryManual(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @PathVariable Long id) {
        if (!validar(secret)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("erro", "Secret inválido"));
        try {
            NotaFiscalEmitida n = emissor.retentarNota(id);
            if (n == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("erro", "Nota não encontrada"));
            return ResponseEntity.ok(Map.of("ok", true, "status", n.getStatus().name(),
                    "tentativas", n.getTentativas()));
        } catch (Exception e) {
            log.error("[Fiscal][Admin] erro no retry manual da nota {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("erro", e.getMessage()));
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────
    private boolean validar(String secret) {
        if (esperado == null || esperado.isBlank()) {
            log.warn("[FiscalAdmin] validar FALSE: esperado vazio (env ADMIN_INTERNAL_SECRET nao lida)");
            return false;
        }
        String esp = esperado.trim();
        String rec = secret == null ? "" : secret.trim();
        boolean ok = esp.equals(rec);
        if (!ok) {
            log.warn("[FiscalAdmin] validar FALSE: esperadoLen={} recebidoLen={} " +
                     "esperadoInicio={} recebidoInicio={} match(afterTrim)={}",
                     esp.length(), rec.length(),
                     esp.substring(0, Math.min(6, esp.length())),
                     rec.substring(0, Math.min(6, rec.length())),
                     esp.equals(rec));
        }
        return ok;
    }

    private Map<String, Object> serializar(NotaFiscalEmitida n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("restauranteId", n.getRestaurante() == null ? null : n.getRestaurante().getId());
        m.put("restauranteNome", n.getRestaurante() == null ? null : n.getRestaurante().getNome());
        m.put("cnpj", n.getCnpj());
        m.put("pedidoId", n.getPedidoId());
        m.put("modelo", n.getModelo());
        m.put("serie", n.getSerie());
        m.put("numero", n.getNumero());
        m.put("ambiente", n.getAmbiente());
        m.put("status", n.getStatus().name());
        m.put("cStat", n.getSefazCstat());
        m.put("motivo", n.getSefazMotivo());
        m.put("chaveAcesso", n.getChaveAcesso());
        m.put("protocolo", n.getProtocolo());
        m.put("valorTotal", n.getValorTotal());
        m.put("tentativas", n.getTentativas());
        m.put("xmlUrl", n.getXmlUrl());
        m.put("qrCodeUrl", n.getQrcodeUrlConsulta());
        m.put("emitidaEm", n.getEmitidaEm() == null ? null : n.getEmitidaEm().toString());
        m.put("criadoEm", n.getCriadoEm() == null ? null : n.getCriadoEm().toString());
        return m;
    }
}
