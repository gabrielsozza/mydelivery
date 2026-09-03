package com.mydelivery.fiscal.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Feature flag central do módulo fiscal. TUDO que emite/consulta nota
 * consulta este bean antes de agir. Enquanto {@link #isAtivo()} for false,
 * o painel do dono não mostra a aba, os endpoints devolvem 503 e as lojas
 * ativas não veem NADA — deploy seguro em produção sem impactar ninguém.
 */
@Slf4j
@Getter
@Component
public class FiscalConfig {

    private final boolean ativo;
    private final Set<String> betaSlugs;
    private final boolean betaLiberadoParaTodos;

    public FiscalConfig(
            @Value("${mydelivery.fiscal.ativo:false}") boolean ativo,
            @Value("${mydelivery.fiscal.beta-slugs:}") String betaSlugsRaw) {
        this.ativo = ativo;
        String raw = betaSlugsRaw == null ? "" : betaSlugsRaw.trim();
        this.betaLiberadoParaTodos = "*".equals(raw);
        this.betaSlugs = raw.isEmpty() || "*".equals(raw)
                ? new HashSet<>()
                : Arrays.stream(raw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(String::toLowerCase)
                        .collect(Collectors.toSet());
        if (ativo) {
            log.info("[Fiscal] Módulo ATIVO — beta={} slugs={}",
                    betaLiberadoParaTodos ? "TODOS" : "restrito", betaSlugs);
        } else {
            log.info("[Fiscal] Módulo DESATIVADO (FISCAL_ATIVO=false). Endpoints /fiscal → 503.");
        }
    }

    /**
     * Retorna true se o slug está no beta-slug (uso legado / testes internos).
     * Nova checagem principal é {@link #autorizadoParaRestaurante} — usa o
     * add-on comercial contratado pelo dono. Beta-slug continua como override
     * pra permitir testes sem ter que "comprar" o plano.
     */
    public boolean autorizadoParaSlug(String slug) {
        if (!ativo) return false;
        if (betaLiberadoParaTodos) return true;
        if (slug == null) return false;
        return betaSlugs.contains(slug.toLowerCase().trim());
    }

    /**
     * Autorizado se: módulo global ATIVO E (loja tem add-on fiscal contratado
     * OU está na beta-slug). Um dos dois já libera — beta-slug pra QA interno,
     * fiscalHabilitado pra clientes reais que assinaram.
     */
    public boolean autorizadoParaRestaurante(com.mydelivery.model.Restaurante r) {
        if (!ativo || r == null) return false;
        if (Boolean.TRUE.equals(r.getFiscalHabilitado())) return true;
        return autorizadoParaSlug(r.getSlug());
    }
}
