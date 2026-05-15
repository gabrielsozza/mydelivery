package com.mydelivery.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.Restaurante;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.RestauranteRepository;
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
