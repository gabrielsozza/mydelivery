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
        var cfg = configRestauranteRepository.findByRestauranteId(r.getId()).orElse(null);
        r.setPixOnlineDisponivel(com.mydelivery.service.mercadopago.MercadoPagoDisponibilidade.pix(cfg));
        r.setCartaoOnlineDisponivel(com.mydelivery.service.mercadopago.MercadoPagoDisponibilidade.cartao(cfg));
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
    @com.mydelivery.equipe.PermissaoRequerida(com.mydelivery.equipe.Permissao.ALTERAR_CONFIGURACOES)
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
                    "configurado", false,
                    "pixDisponivel", false,
                    "cartaoDisponivel", false,
                    "pendencias", com.mydelivery.service.mercadopago.MercadoPagoDisponibilidade.pendencias(null)));
        }
        return ResponseEntity.ok(Map.of(
                "mpAccessTokenMasked", mascarar(cfg.getMpAccessToken()),
                "mpPublicKey", cfg.getMpPublicKey() != null ? cfg.getMpPublicKey() : "",
                "mpWebhookSecretMasked", mascarar(cfg.getMpWebhookSecret()),
                "configurado", com.mydelivery.service.mercadopago.MercadoPagoDisponibilidade.cartao(cfg),
                "pixDisponivel", com.mydelivery.service.mercadopago.MercadoPagoDisponibilidade.pix(cfg),
                "cartaoDisponivel", com.mydelivery.service.mercadopago.MercadoPagoDisponibilidade.cartao(cfg),
                "pendencias", com.mydelivery.service.mercadopago.MercadoPagoDisponibilidade.pendencias(cfg)));
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
                "mensagem", erro != null ? erro : "Access Token válido. Salve as credenciais e configure o webhook para confirmar os pagamentos automaticamente."));
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

    /**
     * Atualiza somente a disponibilidade manual da loja. Mantém o atalho da
     * tela de pedidos independente do salvamento das demais configurações.
     */
    @PatchMapping("/api/restaurante/configuracoes/status-loja")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @com.mydelivery.equipe.PermissaoRequerida(com.mydelivery.equipe.Permissao.ALTERAR_CONFIGURACOES)
    public ResponseEntity<Map<String, Boolean>> atualizarStatusLoja(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Boolean> body) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        if (body == null || body.get("aberto") == null) {
            return ResponseEntity.badRequest().body(Map.of("aberto", Boolean.TRUE.equals(r.getAberto())));
        }
        boolean aberto = Boolean.TRUE.equals(body.get("aberto"));
        r.setAberto(aberto);
        r.setStatusManualAte(horarioLojaService.proximoLimite(r, aberto));
        restauranteRepository.save(r);
        return ResponseEntity.ok(Map.of("aberto", aberto));
    }

    @PatchMapping("/api/restaurante/configuracoes/somente-retirada")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @com.mydelivery.equipe.PermissaoRequerida(com.mydelivery.equipe.Permissao.ALTERAR_CONFIGURACOES)
    public ResponseEntity<Map<String, Boolean>> atualizarSomenteRetirada(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Boolean> body) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        if (body == null || body.get("ativo") == null) {
            return ResponseEntity.badRequest().body(Map.of("somenteRetiradaAtivo", Boolean.TRUE.equals(r.getSomenteRetiradaAtivo())));
        }
        boolean ativo = Boolean.TRUE.equals(body.get("ativo"));
        if (ativo && (r.getModos() == null || !r.getModos().contains("retirada"))) {
            java.util.List<String> modos = new java.util.ArrayList<>(r.getModos() == null ? java.util.List.of() : r.getModos());
            modos.add("retirada");
            r.setModos(modos);
        }
        r.setSomenteRetiradaAtivo(ativo);
        restauranteRepository.save(r);
        return ResponseEntity.ok(Map.of("somenteRetiradaAtivo", ativo));
    }

    /**
     * Preferência de IMPRESSÃO AUTOMÁTICA persistida por restaurante. Antes vivia
     * só no localStorage e o logout/401 (localStorage.clear) apagava — o dono
     * reconfigurava todo dia. Agora o painel re-hidrata no boot por este GET e
     * salva por este PATCH. É só a PREFERÊNCIA; a conexão física é reestabelecida
     * pelo próprio módulo de impressão (WebUSB getDevices/RawBT/nativa).
     */
    @GetMapping("/api/restaurante/configuracoes/impressao-automatica")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String, Boolean>> getImpressaoAutomatica(
            @AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        // configurado=false → nunca setou (front migra o localStorage). Assim não
        // desligamos quem já tinha a impressão ligada antes deste campo existir.
        return ResponseEntity.ok(Map.of(
                "ativo", Boolean.TRUE.equals(r.getImpressaoAutomatica()),
                "configurado", r.getImpressaoAutomatica() != null));
    }

    @PatchMapping("/api/restaurante/configuracoes/impressao-automatica")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String, Boolean>> toggleImpressaoAutomatica(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Boolean> body) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        boolean novo = Boolean.TRUE.equals(body.get("ativo"));
        r.setImpressaoAutomatica(novo);
        restauranteRepository.save(r);
        return ResponseEntity.ok(Map.of("impressaoAutomatica", novo));
    }
}
