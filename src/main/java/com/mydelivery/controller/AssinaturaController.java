package com.mydelivery.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.Assinatura;
import com.mydelivery.model.Plano;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.AssinaturaService;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints da área de Planos. Todos exigem ROLE_RESTAURANTE.
 *
 * /status   → estado completo (fase, dias, planos disponíveis) pro frontend
 * /assinar  → marca um plano como ativo (chamado APÓS confirmação de pagamento)
 *
 * Observação: o fluxo de cobrança usa o módulo /api/pagamentos/* (Mercado Pago)
 * já implementado. Quando o pagamento é confirmado via webhook, este controller
 * é chamado pra promover Restaurante.status para ATIVO.
 */
@RestController
@RequestMapping("/api/restaurante/assinatura")
@RequiredArgsConstructor
public class AssinaturaController {

    private final AssinaturaService assinaturaService;
    private final RestauranteRepository restauranteRepository;

    @GetMapping("/status")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> status(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(assinaturaService.obterStatus(r));
    }

    /**
     * Ativa o plano. Body: { plano: "MENSAL"|"SEMESTRAL"|"ANUAL", metodoPagamento: "PIX"|"CARTAO" }
     * Em produção, este endpoint é idealmente chamado pelo webhook do MP após confirmação.
     * Em dev/MVP, aceita chamada direta do front depois do pagamento.
     */
    @PostMapping("/assinar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> assinar(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        String planoStr = body.get("plano");
        if (planoStr == null) throw new RuntimeException("Informe o plano");
        Plano plano;
        try {
            plano = Plano.valueOf(planoStr.toUpperCase());
        } catch (Exception e) {
            throw new RuntimeException("Plano inválido");
        }
        // Aceita os 2 nomes pra compatibilidade com chamadas antigas
        String metodo = body.getOrDefault("metodo", body.getOrDefault("metodoPagamento", "CARTAO"));
        metodo = metodo == null ? "CARTAO" : metodo.toUpperCase();
        String refGateway = body.getOrDefault("referenciaGateway", null);

        // Regra de negócio: PIX só pra planos > 1 mês.
        // Mensal só aceita cartão (cobrança recorrente).
        if ("PIX".equals(metodo) && plano.getDuracaoMeses() <= 1) {
            throw new RuntimeException("PIX disponível apenas para planos Semestral ou Anual. "
                    + "O plano Mensal aceita apenas cartão de crédito.");
        }

        Assinatura a = assinaturaService.ativarPlano(r, plano, metodo, refGateway);
        return ResponseEntity.ok(Map.of(
                "status", a.getStatus().name(),
                "plano", a.getPlano().name(),
                "validaAte", a.getValidaAte().toString(),
                "mensagem", "Bem-vindo ao plano " + plano.getNomeExibicao() + "! 🎉"
        ));
    }

    /**
     * Cancela o plano vigente. Acesso mantido até o fim do período pago
     * (validaAte) — após isso entra em RESTRICAO/BLOQUEIO conforme regras.
     */
    @PostMapping("/cancelar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> cancelar(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        Assinatura a = assinaturaService.cancelarPlano(r);
        return ResponseEntity.ok(Map.of(
                "status", a.getStatus().name(),
                "validaAte", a.getValidaAte() != null ? a.getValidaAte().toString() : "",
                "mensagem", "Plano cancelado. Você ainda tem acesso até " + a.getValidaAte() + "."
        ));
    }
}
