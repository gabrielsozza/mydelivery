package com.mydelivery.config;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * CorrelationId por request (Jul/2026 v2).
 *
 * Motivação: até agora os logs eram "planos" — impossível correlacionar
 * "esse erro veio da mesma request desse warn?" porque tudo virava linha
 * solta no Railway.
 *
 * Agora: cada request HTTP gera um cid curto (8 chars). Todo log emitido
 * durante essa request carrega {cid=abcd1234} no MDC. Isso permite:
 *   railway logs | grep 'cid=abcd1234'
 * e ver TUDO que aconteceu naquela request específica.
 *
 * Também colamos no response header X-Correlation-Id — se o cliente
 * reporta bug com screenshot, dá pra pegar o header e greppar log.
 *
 * Ordem: HIGHEST_PRECEDENCE +5 pra rodar cedo (antes de qualquer log).
 * Também registrado via FilterRegistrationBean (não conflita com Spring
 * Security).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "cid";
    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // Se cliente já mandou X-Correlation-Id, respeita (permite tracing
        // ponta-a-ponta entre serviços). Senão, gera novo curto.
        String cid = req.getHeader(HEADER);
        if (cid == null || cid.isBlank() || cid.length() > 32) {
            cid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        MDC.put(MDC_KEY, cid);
        res.setHeader(HEADER, cid);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * FIX crítico: Spring Boot auto-registra qualquer OncePerRequestFilter com
     * @Component como filter global via FilterRegistrationBean E outro bean
     * pode registrar duplicado. Aqui é um só, mas se algum dia o Security
     * chain também registrar, seguraria contra dupla execução.
     */
    @org.springframework.context.annotation.Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<CorrelationIdFilter>
            disableAutoRegistration(CorrelationIdFilter filter) {
        var reg = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }
}
