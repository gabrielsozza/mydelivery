package com.mydelivery.security;

import java.io.IOException;
import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Blindagem do modo demo: quando o afiliado abre o sistema em sessão demo
 * (JWT com claim {@code demo=true}), este filter bloqueia mutações
 * potencialmente destrutivas ou que gerariam efeito colateral externo
 * (envio real de WhatsApp, transação de pagamento, alteração de config
 * sensível, etc). A ideia é: afiliado navega à vontade, mas não
 * consegue quebrar nem gastar dinheiro real.
 *
 * O que está PERMITIDO na demo (pra ele conseguir demonstrar tudo):
 *  - GET em qualquer coisa
 *  - Simular status de pedido (PATCH /pedidos/x/status)
 *  - Criar/editar produto do cardápio (mas cron reseta 3h da manhã)
 *  - Login e refresh token
 *
 * O que está BLOQUEADO:
 *  - Envio de WhatsApp real (/api/whatsapp/**)
 *  - Config crítica (chave PIX, integração iFood, assinatura, plano)
 *  - Pagamentos (nenhuma transação real)
 *  - DELETE em qualquer coisa (evita apagar seed)
 *  - Endpoints de admin
 *
 * Roda DEPOIS do JwtAuthenticationFilter — precisa do token já parseado.
 * Se rejeitar, devolve 403 com mensagem clara.
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class DemoModeFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    /**
     * Rotas com efeito colateral externo — nunca podem ser chamadas em demo.
     * (Prefixos — o filter faz startsWith.)
     */
    private static final List<String> ROTAS_BLOQUEADAS = List.of(
            "/api/whatsapp/",           // envio de mensagem WhatsApp
            "/api/integracao/whatsapp/",// config Evolution / QR code / reconnect
            "/api/iFood/",              // integração iFood
            "/api/ifood/",              // variação de casing
            "/api/pagamentos/",         // transações reais
            "/api/webhooks/",           // webhook não deveria vir de sessão logada mesmo
            "/api/plano/",              // troca de plano de assinatura
            "/api/assinatura/",         // upgrade/downgrade/cancelar
            "/api/admin/",              // qualquer coisa admin
            "/api/admin-internal/"      // endpoints internos
    );

    /**
     * Substrings de path que indicam config crítica do restaurante — bloqueia
     * PUT/PATCH/POST nelas mas deixa GET passar (pra afiliado poder MOSTRAR a tela).
     */
    private static final List<String> CONFIG_CRITICA_SUBSTR = List.of(
            "/config/pix",
            "/config/pagamento",
            "/config/dominio",
            "/webhook",
            "/apikey",
            "/api-key"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        // Detecta se é sessão demo — precisa parsear token de novo aqui porque
        // JwtAuthenticationFilter põe email + role no SecurityContext mas não
        // claim demo. Custo é ~microsegundos, roda só se tem Authorization.
        String authHeader = req.getHeader("Authorization");
        boolean isDemo = false;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try { isDemo = jwtUtil.isDemo(token); } catch (Exception ignore) { /* segue */ }
        }
        if (!isDemo) {
            chain.doFilter(req, res);
            return;
        }

        String path = req.getRequestURI();
        String method = req.getMethod();

        // 1) DELETE bloqueado em qualquer coisa
        if ("DELETE".equalsIgnoreCase(method)) {
            negar(res, "Modo demo: exclusão desabilitada. Aqui é só pra você mostrar o sistema.");
            return;
        }

        // 2) Rotas com efeito colateral externo — bloquear TODOS os métodos exceto GET
        boolean rotaBloqueada = ROTAS_BLOQUEADAS.stream().anyMatch(path::startsWith);
        if (rotaBloqueada && !"GET".equalsIgnoreCase(method)) {
            negar(res, "Modo demo: essa ação envolveria efeito real (WhatsApp/pagamento/config crítica) e ficou desabilitada.");
            return;
        }

        // 3) Config crítica dentro de /api/restaurante — bloquear mutação
        if (!"GET".equalsIgnoreCase(method) && path.startsWith("/api/restaurante/")) {
            String lower = path.toLowerCase();
            boolean configCritica = CONFIG_CRITICA_SUBSTR.stream().anyMatch(lower::contains);
            if (configCritica) {
                negar(res, "Modo demo: alterar essa configuração ficou desabilitado pra segurança.");
                return;
            }
        }

        chain.doFilter(req, res);
    }

    private void negar(HttpServletResponse res, String msg) throws IOException {
        log.debug("DemoModeFilter: bloqueou → {}", msg);
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json;charset=UTF-8");
        // JSON escapado à mão pra não puxar dependência do Jackson
        String safe = msg.replace("\\", "\\\\").replace("\"", "\\\"");
        res.getWriter().write("{\"erro\":\"" + safe + "\",\"demo\":true}");
    }
}
