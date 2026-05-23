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
import com.mydelivery.service.AssinaturaPagamentoService;
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
    private final AssinaturaPagamentoService pagamentoService;
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
     * Inicia cobrança real no Mercado Pago.
     * Body: { plano, metodo: "PIX"|"CARTAO", returnBaseUrl? }
     *
     * Resposta PIX: { tipo:"PIX", paymentId, qrCode, qrCodeBase64, expiraEm }
     * Resposta CARTÃO: { tipo:"CHECKOUT_URL", checkoutUrl } (frontend redireciona)
     */
    @PostMapping("/iniciar-pagamento")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> iniciarPagamento(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        Plano plano;
        try { plano = Plano.valueOf(body.getOrDefault("plano", "").toUpperCase()); }
        catch (Exception e) { throw new RuntimeException("Plano inválido"); }

        String metodo = body.getOrDefault("metodo", "CARTAO").toUpperCase();
        // Regra: PIX só pra planos > 1 mês
        if ("PIX".equals(metodo) && plano.getDuracaoMeses() <= 1) {
            throw new RuntimeException("PIX disponível apenas para planos Semestral ou Anual.");
        }

        if ("PIX".equals(metodo)) {
            return ResponseEntity.ok(pagamentoService.criarPix(r, plano));
        }
        String returnBase = body.getOrDefault("returnBaseUrl", "https://mydeliveryfood.com.br");
        return ResponseEntity.ok(pagamentoService.criarCheckoutCartao(r, plano, returnBase));
    }

    /**
     * Public Key MP — frontend usa pra inicializar o Card Payment Brick.
     * Não é secret: pode ir pro front com segurança. Backend só expõe pra
     * evitar hardcode no frontend.
     */
    @GetMapping("/mp-public-key")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> mpPublicKey() {
        return ResponseEntity.ok(pagamentoService.publicKeyInfo());
    }

    /**
     * Processa pagamento por cartão via TOKEN do Card Payment Brick.
     * Body: { plano, formData: { token, installments, payment_method_id, payer: {...} } }
     *
     * O cartão real é tokenizado no front (SDK MP) — backend só recebe o token,
     * sem PAN/CVV. PCI compliant.
     */
    @PostMapping("/pagar-cartao")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> pagarCartao(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        Plano plano;
        try { plano = Plano.valueOf(String.valueOf(body.getOrDefault("plano", "")).toUpperCase()); }
        catch (Exception e) { throw new RuntimeException("Plano inválido"); }
        Map<String, Object> formData = (Map<String, Object>) body.getOrDefault("formData", Map.of());

        // ── Regra de negócio: se o restaurante AINDA está em TRIAL,
        // NÃO cobra agora. Salva o cartão no MP (Customer + Card) e marca
        // assinatura como PROGRAMADA pra cobrar quando trial expirar.
        boolean emTrial = r.getStatus() == Restaurante.Status.TRIAL
                && r.getTrialExpiraEm() != null
                && r.getTrialExpiraEm().isAfter(java.time.LocalDateTime.now());

        if (emTrial) {
            Map<String, Object> resp = pagamentoService.salvarCartaoParaTrial(r, plano, formData);
            // Assinatura fica como PROGRAMADA — restaurante continua usando TRIAL até a data.
            Assinatura a = assinaturaService.programarPlanoTrialCartao(r, plano,
                    (String) resp.get("referenciaGateway"));
            resp.put("validaAte", a.getValidaAte().toString());
            resp.put("cobrarEm", r.getTrialExpiraEm().toString());
            resp.put("mensagem", "Cartão validado! A primeira cobrança será automática ao fim do período de avaliação.");
            return ResponseEntity.ok(resp);
        }

        // Sem trial → cobrança imediata (fluxo padrão)
        Map<String, Object> resp = pagamentoService.pagarCartao(r, plano, formData);
        if (Boolean.TRUE.equals(resp.get("aprovado"))) {
            Assinatura a = assinaturaService.ativarPlano(r, plano, "CARTAO",
                    resp.get("paymentId") == null ? null : String.valueOf(resp.get("paymentId")));
            resp.put("validaAte", a.getValidaAte().toString());
        }
        return ResponseEntity.ok(resp);
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

    /**
     * Troca de plano (upgrade ou downgrade).
     * Body: { plano: "MENSAL"|"SEMESTRAL"|"ANUAL" }
     *
     * UPGRADE: calcula crédito proporcional do plano atual e cobra a diferença
     *          (frontend redireciona pro Brick com valorACobrar).
     * DOWNGRADE: agenda — plano atual continua até validaAte, depois entra o novo.
     */
    @PostMapping("/trocar-plano")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> trocarPlano(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        Plano novo;
        try { novo = Plano.valueOf(body.getOrDefault("plano", "").toUpperCase()); }
        catch (Exception e) { throw new RuntimeException("Plano inválido"); }
        return ResponseEntity.ok(assinaturaService.trocarPlano(r, novo));
    }

    /**
     * Troca o método de pagamento (PIX↔CARTAO). Não cobra nada.
     * Se for trocar pra CARTAO sem cartão salvo, frontend deve chamar
     * /pagar-cartao ou /atualizar-cartao em seguida.
     * Body: { metodo: "PIX"|"CARTAO" }
     */
    @PostMapping("/trocar-metodo")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> trocarMetodo(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        Assinatura a = assinaturaService.trocarMetodo(r, body.get("metodo"));
        return ResponseEntity.ok(Map.of(
                "metodoPagamento", a.getMetodoPagamento(),
                "mensagem", "Método de pagamento atualizado pra " + a.getMetodoPagamento()
        ));
    }

    /**
     * Substitui o cartão salvo. Frontend gera token novo via Brick e envia aqui.
     * Body: { formData: { token, payer:{...} } }
     */
    @PostMapping("/atualizar-cartao")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> atualizarCartao(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        Restaurante r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        Map<String, Object> formData = (Map<String, Object>) body.getOrDefault("formData", Map.of());

        // Pega referência atual (se houver) pra reusar customer
        Assinatura aAtual = restauranteRepository.findById(r.getId())
                .flatMap(rr -> assinaturaService.obterAssinatura(rr)).orElse(null);
        String refAtual = aAtual != null ? aAtual.getReferenciaGateway() : null;

        String novaRef = pagamentoService.atualizarCartao(r, refAtual, formData);
        Assinatura a = assinaturaService.atualizarReferenciaCartao(r, novaRef);
        return ResponseEntity.ok(Map.of(
                "metodoPagamento", a.getMetodoPagamento(),
                "mensagem", "Cartão atualizado com sucesso."
        ));
    }
}
