package com.mydelivery.fiscal.config;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mydelivery.model.Assinatura;
import com.mydelivery.repository.AssinaturaRepository;

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
    private final AssinaturaRepository assinaturaRepo;

    public FiscalConfig(
            @Value("${mydelivery.fiscal.ativo:false}") boolean ativo,
            @Value("${mydelivery.fiscal.beta-slugs:}") String betaSlugsRaw,
            AssinaturaRepository assinaturaRepo) {
        this.ativo = ativo;
        this.assinaturaRepo = assinaturaRepo;
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
     * Autorizado se: módulo global ATIVO E uma das condicoes abaixo:
     *   1. Loja tem add-on fiscal contratado (fiscalHabilitado=true) — cliente
     *      no plano completo (R$ 99,90) com fiscal.
     *   2. Loja está no TRIAL — assim novo cadastro e trial usam fiscal de graça
     *      e decide se quer contratar quando o trial acabar.
     *   3. Slug está na beta-slug — QA interno.
     */
    public boolean autorizadoParaRestaurante(com.mydelivery.model.Restaurante r) {
        if (!ativo || r == null) return false;
        if (Boolean.TRUE.equals(r.getFiscalHabilitado())) return true;
        if (autorizadoParaSlug(r.getSlug())) return true;
        // Novo cadastro / trial: libera automaticamente até assinar o plano
        // sem fiscal (R$ 75). Quando o dono assina o plano completo (R$ 99,90),
        // fiscalHabilitado vira true e a regra do topo pega.
        return estaEmTrial(r.getId());
    }

    private boolean estaEmTrial(Long restauranteId) {
        if (restauranteId == null || assinaturaRepo == null) return false;
        try {
            var opt = assinaturaRepo.findByRestauranteId(restauranteId);
            if (opt.isEmpty()) return true; // ainda nao criou assinatura = novo cadastro
            var a = opt.get();
            if (a.getStatus() != Assinatura.Status.TRIAL) return false;
            var fim = a.getTrialFim();
            return fim == null || fim.isAfter(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[Fiscal] Falha ao checar trial do restaurante {}: {}",
                    restauranteId, e.getMessage());
            return false;
        }
    }
}
