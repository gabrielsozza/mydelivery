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
@lombok.extern.slf4j.Slf4j
@RestController
@RequestMapping("/api/restaurante/assinatura")
@RequiredArgsConstructor
public class AssinaturaController {

    private final AssinaturaService assinaturaService;
    private final AssinaturaPagamentoService pagamentoService;
    private final RestauranteRepository restauranteRepository;
    private final com.mydelivery.repository.AssinaturaRepository assinaturaRepository;
    private final com.mydelivery.security.JwtUtil jwtUtil;
    private final com.mydelivery.service.CardapioReplicaService cardapioReplicaService;

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
        Map<String, Object> resp;
        try {
            resp = pagamentoService.pagarCartao(r, plano, formData);
        } catch (org.springframework.transaction.UnexpectedRollbackException ure) {
            // Defesa: alguma sub-tx interna marcou rollback (constraint/coluna
            // estourou). NÃO tentar registrar falha aqui pq pode cascatear.
            // Loga raw e devolve mensagem amigável.
            log.error("[Pagamento] UnexpectedRollback no pagarCartao: {}", ure.getMessage(), ure);
            throw new RuntimeException("Erro interno ao processar pagamento. Tente novamente em alguns minutos ou use PIX.");
        } catch (RuntimeException ex) {
            // Erro interno/gateway → registra falha pra admin ver. Como
            // registrarFalhaPagamento agora é REQUIRES_NEW + noRollbackFor,
            // não contamina nada caso o próprio registrar falhe.
            try {
                assinaturaService.registrarFalhaPagamento(r, plano, "CARTAO", null, null,
                        ex.getMessage(), com.mydelivery.model.PagamentoMensalidade.CategoriaErro.SISTEMA);
            } catch (Exception swallow) {
                log.warn("[Pagamento] failed to register failure (ignorado): {}", swallow.getMessage());
            }
            throw ex;
        }
        if (Boolean.TRUE.equals(resp.get("aprovado"))) {
            Assinatura a = assinaturaService.ativarPlano(r, plano, "CARTAO",
                    resp.get("paymentId") == null ? null : String.valueOf(resp.get("paymentId")));
            // Registra pagamento bem-sucedido
            assinaturaService.registrarPagamentoOk(r, plano, "CARTAO",
                    resp.get("paymentId") != null ? Long.valueOf(String.valueOf(resp.get("paymentId"))) : null);
            resp.put("validaAte", a.getValidaAte().toString());
        } else {
            // Cartão recusado pelo MP → falha do CLIENTE
            assinaturaService.registrarFalhaPagamento(r, plano, "CARTAO",
                    resp.get("paymentId") != null ? Long.valueOf(String.valueOf(resp.get("paymentId"))) : null,
                    String.valueOf(resp.get("statusDetail")),
                    "Pagamento " + resp.get("status") + " — " + resp.get("statusDetail"),
                    com.mydelivery.model.PagamentoMensalidade.CategoriaErro.CLIENTE);
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
     * USO INTERNO ADMIN — define valores personalizados de plano por restaurante.
     * Body: { restauranteId, valorMensal, valorSemestral, valorAnual }
     * Valores null mantêm o default da tabela `planos` (R$75 novo padrão).
     */
    @PostMapping("/precificar-restaurante-admin")
    public ResponseEntity<Map<String, Object>> precificarRestauranteAdmin(
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestBody Map<String, Object> body,
            @org.springframework.beans.factory.annotation.Value("${mydelivery.admin.internal-secret:}") String esperado) {
        if (esperado == null || esperado.isBlank() || !esperado.equals(secret)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(Map.of("erro", "Secret inválido"));
        }
        Long restauranteId = Long.valueOf(String.valueOf(body.get("restauranteId")));
        Restaurante r = restauranteRepository.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        r.setValorMensalPersonalizado(decOf(body.get("valorMensal")));
        r.setValorSemestralPersonalizado(decOf(body.get("valorSemestral")));
        r.setValorAnualPersonalizado(decOf(body.get("valorAnual")));
        restauranteRepository.save(r);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "restauranteId", r.getId(),
                "valorMensal", r.getValorMensalPersonalizado() == null ? "default" : r.getValorMensalPersonalizado().toString(),
                "valorSemestral", r.getValorSemestralPersonalizado() == null ? "default" : r.getValorSemestralPersonalizado().toString(),
                "valorAnual", r.getValorAnualPersonalizado() == null ? "default" : r.getValorAnualPersonalizado().toString()
        ));
    }

    private java.math.BigDecimal decOf(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equals(s)) return null;
        try { return new java.math.BigDecimal(s); } catch (Exception e) { return null; }
    }

    /**
     * USO INTERNO ADMIN/TESTE — expira o trial de um restaurante imediatamente.
     * Não cobra, não bloqueia — apenas adianta trial_expira_em pra ontem,
     * forçando o restaurante a cair no fluxo de cobrança imediata na próxima
     * chamada de /assinar ou /pagar-cartao.
     *
     * Body: { restauranteId }
     * Header: X-Admin-Secret
     */
    @PostMapping("/expirar-trial-admin")
    public ResponseEntity<Map<String, Object>> expirarTrialAdmin(
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestBody Map<String, Object> body,
            @org.springframework.beans.factory.annotation.Value("${mydelivery.admin.internal-secret:}") String esperado) {
        if (esperado == null || esperado.isBlank() || !esperado.equals(secret)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(Map.of("erro", "Secret inválido"));
        }
        Long restauranteId = Long.valueOf(String.valueOf(body.get("restauranteId")));
        Restaurante r = restauranteRepository.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        java.time.LocalDateTime ontem = java.time.LocalDateTime.now().minusDays(1);
        // mantém status TRIAL — UI mostra "seu trial acabou, contrate plano"
        // em vez de "BLOQUEADO". Check do controller pagarCartao
        // (emTrial = status==TRIAL && trialExpiraEm.isAfter(now())) cai
        // no else porque trialExpiraEm está no passado.
        r.setTrialExpiraEm(ontem);
        restauranteRepository.save(r);
        // CRÍTICO: a UI lê /assinatura/status que usa assinatura.trialFim
        // (não restaurante.trialExpiraEm). Sem mexer nos 2, o painel continua
        // mostrando "32 dias restantes" mesmo com trial expirado no
        // restaurante. Aqui também adianto trialFim + proximaCobranca.
        com.mydelivery.model.Assinatura a = assinaturaRepository
                .findByRestauranteId(restauranteId).orElse(null);
        if (a != null) {
            a.setTrialFim(ontem);
            a.setProximaCobranca(ontem);
            assinaturaRepository.save(a);
        }
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "restauranteId", r.getId(),
                "nome", r.getNome(),
                "trialExpiraEm", ontem.toString(),
                "assinaturaAtualizada", a != null,
                "mensagem", "Trial expirado em restaurantes e assinaturas. Próxima tentativa de pagamento vai cobrar imediatamente."
        ));
    }

    /**
     * USO INTERNO ADMIN — concede meses grátis pra um restaurante.
     * Não exige role RESTAURANTE: protegido por header X-Admin-Secret.
     * Body: { restauranteId, meses, motivo? }
     */
    @PostMapping("/conceder-meses-gratis-admin")
    public ResponseEntity<Map<String, Object>> concederMesesGratisAdmin(
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestBody Map<String, Object> body,
            @org.springframework.beans.factory.annotation.Value("${mydelivery.admin.internal-secret:}") String esperado) {
        if (esperado == null || esperado.isBlank() || !esperado.equals(secret)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(Map.of("erro", "Secret inválido"));
        }
        Long restauranteId = Long.valueOf(String.valueOf(body.get("restauranteId")));
        int meses = Integer.parseInt(String.valueOf(body.getOrDefault("meses", 1)));
        String motivo = (String) body.getOrDefault("motivo", "Concedido pela equipe MyDelivery");

        Restaurante r = restauranteRepository.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        Assinatura a = assinaturaService.concederMesesGratis(r, meses, motivo);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "novoValidaAte", a.getValidaAte() != null ? a.getValidaAte().toString() : "",
                "proximaCobranca", a.getProximaCobranca() != null ? a.getProximaCobranca().toString() : "",
                "mensagem", meses + " mês(es) concedido(s)."
        ));
    }

    /**
     * USO INTERNO ADMIN — gera token JWT curto (15min) pro admin entrar como o dono
     * do restaurante (suporte). Protegido por X-Admin-Secret.
     *
     * Body: { restauranteId, adminIdentificador }
     * Retorna: { accessToken, restauranteSlug, nome, email, expiresIn }
     */
    @PostMapping("/impersonar-admin")
    public ResponseEntity<Map<String, Object>> impersonarAdmin(
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestBody Map<String, Object> body,
            @org.springframework.beans.factory.annotation.Value("${mydelivery.admin.internal-secret:}") String esperado) {
        if (esperado == null || esperado.isBlank() || !esperado.equals(secret)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(Map.of("erro", "Secret inválido"));
        }

        Long restauranteId = Long.valueOf(String.valueOf(body.get("restauranteId")));
        String adminId = String.valueOf(body.getOrDefault("adminIdentificador", "admin-desconhecido"));

        Restaurante r = restauranteRepository.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        var usuario = r.getUsuario();
        if (usuario == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", "Restaurante sem usuário vinculado"));
        }

        String token = jwtUtil.gerarTokenImpersonacao(
            usuario.getEmail(), usuario.getRole().name(), adminId);

        log.warn("[Impersonation] adminId={} entrou como usuario={} restaurante={} ({})",
                adminId, usuario.getEmail(), r.getId(), r.getNome());

        return ResponseEntity.ok(Map.of(
            "accessToken", token,
            "restauranteSlug", r.getSlug() != null ? r.getSlug() : "",
            "nome", usuario.getNome() != null ? usuario.getNome() : "",
            "email", usuario.getEmail(),
            "role", usuario.getRole().name(),
            "expiresIn", 15 * 60 * 1000
        ));
    }

    /**
     * USO INTERNO ADMIN — replica o cardápio da origem pra UMA loja destino.
     * Protegido por X-Admin-Secret.
     *
     * Body: { origemId, destinoId, modo? ("ACRESCENTAR" default | "SUBSTITUIR") }
     * Retorna: { ok, destinoNome, categorias, produtos, grupos, itens, banners, modo }
     */
    @PostMapping("/replicar-cardapio-admin")
    public ResponseEntity<Map<String, Object>> replicarCardapioAdmin(
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestBody Map<String, Object> body,
            @org.springframework.beans.factory.annotation.Value("${mydelivery.admin.internal-secret:}") String esperado) {
        if (esperado == null || esperado.isBlank() || !esperado.equals(secret)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(Map.of("erro", "Secret inválido"));
        }
        try {
            Long origemId = Long.valueOf(String.valueOf(body.get("origemId")));
            Long destinoId = Long.valueOf(String.valueOf(body.get("destinoId")));
            String modo = (String) body.getOrDefault("modo", "ACRESCENTAR");

            var r = cardapioReplicaService.replicar(origemId, destinoId, modo);
            return ResponseEntity.ok(Map.of(
                "ok", true,
                "destinoId", r.destinoId(),
                "destinoNome", r.destinoNome() != null ? r.destinoNome() : "",
                "categorias", r.categorias(),
                "produtos", r.produtos(),
                "grupos", r.grupos(),
                "itens", r.itens(),
                "banners", r.banners(),
                "modo", r.modo()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        } catch (Exception e) {
            log.error("[Replica] erro: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("erro", e.getMessage()));
        }
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
