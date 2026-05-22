package com.mydelivery.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.dto.restaurante.ConfiguracaoRequest;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.ConfiguracaoService;
import com.mydelivery.service.mercadopago.MercadoPagoClient;

import org.springframework.web.bind.annotation.PostMapping;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ConfiguracaoController {

    private final ConfiguracaoService configuracaoService;
    private final RestauranteRepository restauranteRepository;
    private final ConfiguracaoRestauranteRepository configRestauranteRepository;
    private final MercadoPagoClient mercadoPagoClient;
    private final com.mydelivery.service.HorarioLojaService horarioLojaService;

    @GetMapping("/api/restaurante/configuracoes")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Restaurante> getConfiguracoes(
            @AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository
                .findByUsuarioEmail(email)
                .orElseThrow();
        return ResponseEntity.ok(r);
    }

    @GetMapping("/api/restaurante/publico/{slug}")
    public ResponseEntity<Restaurante> getPublico(@PathVariable String slug) {
        Restaurante r = restauranteRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        // Calcula on-the-fly se está aceitando pedidos AGORA (considera cutoff
        // antes do fechamento). null = não-determinado (front trata como true).
        try {
            var estado = horarioLojaService.calcular(r);
            // Loja só aceita se aberta manualmente E dentro do horário E não no cutoff.
            boolean aceita = Boolean.TRUE.equals(r.getAberto()) && !estado.dentroCutoff;
            r.setAceitandoPedidos(aceita);
        } catch (Exception e) {
            r.setAceitandoPedidos(true); // defensivo
        }
        // no-cache + no-store: cliente final sempre vê dados atuais do restaurante
        // (logo, capa, taxa, status aberto/fechado). Imagens Cloudinary já têm UUID
        // e podem ser cacheadas no nível da URL — só o JSON precisa ser fresh.
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .body(r);
    }

    /**
     * Public Key do Mercado Pago do restaurante — usada pelo SDK MP.js no browser
     * pra tokenizar cartão sem expor o access token (que continua restrito ao backend).
     * Endpoint público porque o cliente final precisa antes de logar.
     */
    @GetMapping("/api/restaurante/publico/{slug}/mp-public-key")
    public ResponseEntity<Map<String, String>> getMpPublicKey(@PathVariable String slug) {
        Restaurante r = restauranteRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        String pk = configRestauranteRepository.findByRestauranteId(r.getId())
                .map(cfg -> cfg.getMpPublicKey())
                .orElse(null);
        return ResponseEntity.ok(Map.of("publicKey", pk != null ? pk : ""));
    }

    @PutMapping("/api/restaurante/configuracoes")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Restaurante> salvarConfiguracoes(
            @AuthenticationPrincipal String email,
            @RequestBody ConfiguracaoRequest request) {
        Restaurante r = restauranteRepository
                .findByUsuarioEmail(email)
                .orElseThrow();
        return ResponseEntity.ok(configuracaoService.salvar(r, request));
    }

    /**
     * Credenciais Mercado Pago do restaurante.
     * Retorna mpPublicKey integral (não é segredo) e mpAccessToken/mpWebhookSecret
     * mascarados — admin vê só o sufixo pra confirmar que está cadastrado,
     * sem expor a credencial completa em telas/logs.
     */
    @GetMapping("/api/restaurante/configuracoes/mercadopago")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> getMercadoPagoConfig(
            @AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        var cfg = configRestauranteRepository.findByRestauranteId(r.getId()).orElse(null);
        if (cfg == null) {
            return ResponseEntity.ok(Map.of(
                    "mpAccessTokenMasked", "",
                    "mpPublicKey", "",
                    "mpWebhookSecretMasked", "",
                    "configurado", false));
        }
        return ResponseEntity.ok(Map.of(
                "mpAccessTokenMasked", mascarar(cfg.getMpAccessToken()),
                "mpPublicKey", cfg.getMpPublicKey() != null ? cfg.getMpPublicKey() : "",
                "mpWebhookSecretMasked", mascarar(cfg.getMpWebhookSecret()),
                "configurado", cfg.getMpAccessToken() != null && !cfg.getMpAccessToken().isBlank()));
    }

    /**
     * Valida o Access Token testando uma chamada leve ao MP (GET /users/me).
     * Aceita token no body pra validar ANTES de salvar; se body vier vazio,
     * usa o token já salvo. Retorna { ok: bool, mensagem: string }.
     */
    @PostMapping("/api/restaurante/configuracoes/mercadopago/validar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> validarMercadoPago(
            @AuthenticationPrincipal String email,
            @RequestBody(required = false) Map<String, String> body) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        String token = body != null ? body.get("accessToken") : null;
        if (token == null || token.isBlank()) {
            token = configRestauranteRepository.findByRestauranteId(r.getId())
                    .map(cfg -> cfg.getMpAccessToken()).orElse(null);
        }
        if (token == null || token.isBlank()) {
            return ResponseEntity.ok(Map.of("ok", false, "mensagem", "Cole o Access Token primeiro."));
        }
        String erro = mercadoPagoClient.validarToken(token);
        return ResponseEntity.ok(Map.of(
                "ok", erro == null,
                "mensagem", erro != null ? erro : "Conexão validada com sucesso."));
    }

    private String mascarar(String s) {
        if (s == null || s.isBlank()) return "";
        if (s.length() <= 8) return "••••••";
        return "••••••" + s.substring(s.length() - 4);
    }

    /**
     * Toggle rápido para aceitar pedidos automaticamente. Endpoint dedicado
     * (não usa o PUT geral) pra simplificar o uso do switch no topo da aba Pedidos.
     * Body: { "ativo": true|false }
     */
    @PatchMapping("/api/restaurante/configuracoes/auto-aceitar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Boolean>> toggleAutoAceitar(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Boolean> body) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        boolean novo = Boolean.TRUE.equals(body.get("ativo"));
        r.setAceitarPedidosAutomaticamente(novo);
        restauranteRepository.save(r);
        return ResponseEntity.ok(Map.of("aceitarPedidosAutomaticamente", novo));
    }
}
