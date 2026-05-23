package com.mydelivery.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.Restaurante;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.whatsapp.WhatsappBotService;
import com.mydelivery.service.whatsapp.WhatsappService;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints do dono do restaurante pra gerenciar a integração WhatsApp.
 * Todos exigem ROLE_RESTAURANTE — não há acesso público a esses recursos.
 */
@RestController
@RequestMapping("/api/whatsapp")
@RequiredArgsConstructor
public class WhatsappController {

    private final WhatsappService whatsappService;
    private final WhatsappBotService botService;
    private final RestauranteRepository restauranteRepository;

    /**
     * Conecta/recupera instância. Devolve QR Code em base64 se aguardando scan,
     * ou estado atual se já conectada. Frontend deve fazer polling em /status
     * pra detectar quando o usuário scaneou.
     */
    @PostMapping("/conectar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> conectar(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        WhatsappInstance inst = whatsappService.conectar(r);
        return ResponseEntity.ok(serializar(inst));
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> status(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        WhatsappInstance inst = whatsappService.status(r);
        return ResponseEntity.ok(serializar(inst));
    }

    @PostMapping("/desconectar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, String>> desconectar(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        whatsappService.desconectar(r);
        return ResponseEntity.ok(Map.of("status", "DESCONECTADA"));
    }

    /** Toggle do bot — 1 clique. Body: { ativo: true|false } */
    @PatchMapping("/bot")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> toggleBot(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Boolean> body) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        boolean ativo = Boolean.TRUE.equals(body.get("ativo"));
        WhatsappInstance inst = whatsappService.toggleBot(r, ativo);
        return ResponseEntity.ok(Map.of("botAtivo", inst.getBotAtivo()));
    }

    // ── Atendimento humano (handoff) ──

    /** Lista números que estão sendo atendidos por humano (bot em silêncio). */
    @GetMapping("/atendimentos-humanos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> listarAtendimentosHumanos(
            @AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        WhatsappInstance inst = whatsappService.status(r);
        List<Map<String, Object>> out = botService.listarAtendimentosHumanos(inst).stream()
                .map(a -> Map.of(
                        "numero", (Object) a.numero(),
                        "silencioAte", (Object) a.silencioAte().toString()))
                .toList();
        return ResponseEntity.ok(out);
    }

    /** Devolve um número pro bot — bot volta a responder esse cliente. */
    @DeleteMapping("/atendimentos-humanos/{numero}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> devolverParaBot(
            @AuthenticationPrincipal String email,
            @PathVariable String numero) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        WhatsappInstance inst = whatsappService.status(r);
        boolean ok = botService.devolverParaBot(inst, numero);
        return ResponseEntity.ok(Map.of("liberado", ok));
    }

    /**
     * Reinicia a sessão WhatsApp SEM logout/QR novo. Útil pra "destravar" quando
     * o robô parece dormir (shadow-ban silencioso do WhatsApp).
     * Roda automaticamente a cada 18h via WhatsappReconnectJob também.
     */
    @PostMapping("/restart")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> restart(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        whatsappService.restart(r);
        return ResponseEntity.ok(Map.of("ok", true, "mensagem", "Sessão reiniciada"));
    }

    /**
     * RESET FORÇADO — apaga a instância na Evolution e localmente. Use quando
     * a instância está em "estado zumbi" (Evolution reporta open mas mensagens
     * não chegam — shadow-ban do WhatsApp). Próximo /conectar cria nova do zero.
     */
    @PostMapping("/reset")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, String>> resetar(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        whatsappService.resetar(r);
        return ResponseEntity.ok(Map.of("status", "RESETADA"));
    }

    // ── Diagnóstico do webhook (uso operacional/suporte) ──

    /** Devolve a configuração de webhook salva na Evolution pra essa instância. */
    @GetMapping("/diag/webhook")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> diagWebhook(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(whatsappService.diagWebhook(r));
    }

    /** Re-configura o webhook pra URL atual do backend. Use se trocou EVOLUTION_WEBHOOK_BASE_URL. */
    @PostMapping("/diag/webhook/reset")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> resetWebhook(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(whatsappService.resetWebhook(r));
    }

    /** Serialização: NÃO devolve instanceToken (segredo). */
    private Map<String, Object> serializar(WhatsappInstance inst) {
        Map<String, Object> m = new HashMap<>();
        m.put("instanceName", inst.getInstanceName());
        m.put("status", inst.getStatus() != null ? inst.getStatus().name() : "NOVA");
        m.put("phone", inst.getPhone() != null ? inst.getPhone() : "");
        m.put("qrCode", inst.getQrCode() != null ? inst.getQrCode() : "");
        m.put("qrExpiraEm", inst.getQrExpiraEm() != null ? inst.getQrExpiraEm().toString() : "");
        m.put("conectadoEm", inst.getConectadoEm() != null ? inst.getConectadoEm().toString() : "");
        m.put("botAtivo", Boolean.TRUE.equals(inst.getBotAtivo()));
        return m;
    }
}
