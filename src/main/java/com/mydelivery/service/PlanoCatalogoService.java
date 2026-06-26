package com.mydelivery.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import com.mydelivery.model.PlanoCatalogo;
import com.mydelivery.repository.PlanoCatalogoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service do catálogo de planos editável. Substitui o uso direto do enum
 * {@code Plano} na exibição/listagem.
 *
 * Estratégia de boot:
 *  - Se a tabela {@code planos_catalog} estiver vazia, faz SEED com os 3 planos
 *    históricos (MENSAL/SEMESTRAL/ANUAL) — garante backward-compat em deploy
 *    inicial e em ambientes novos. Só roda 1 vez.
 *  - Se o admin tiver editado / removido, NÃO restaura — respeita o estado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanoCatalogoService {

    private final PlanoCatalogoRepository repo;

    /** Lista pública, ordenada, somente ativos. Usado por restaurantes. */
    public List<PlanoCatalogo> listarAtivos() {
        return repo.findByAtivoTrueOrderByOrdemAscIdAsc();
    }

    /**
     * Retorna o valor REAL do plano lendo da tabela `planos` (editável pelo
     * admin). Cai pro valor hardcoded do enum como fallback caso não exista
     * registro no banco — defesa pra não quebrar cobrança em ambiente novo
     * sem seed.
     *
     * IMPORTANTE: USAR ESSE MÉTODO em vez de plano.getValor() em TODO ponto
     * que vá pro Mercado Pago (transaction_amount, items.unit_price, etc).
     * Senão admin edita preço mas MP segue cobrando o do enum.
     */
    public BigDecimal valorAtual(com.mydelivery.model.Plano plano) {
        if (plano == null) return BigDecimal.ZERO;
        return repo.findByCodigoIgnoreCase(plano.name())
                .map(PlanoCatalogo::getValor)
                .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) > 0)
                .orElse(plano.getValor());
    }

    /** Lista completa (inclusive desativados). Usado pelo admin. */
    public List<PlanoCatalogo> listarTodos() {
        return repo.findAllByOrderByOrdemAscIdAsc();
    }

    /**
     * Serializa um plano no formato esperado pelo frontend do restaurante
     * (mesmas chaves do antigo `listarPlanos()` do AssinaturaService).
     * Mantém compatibilidade — frontend planos.html não precisa mudar.
     */
    public Map<String, Object> toMapRestaurante(PlanoCatalogo p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getCodigo());
        m.put("nome", p.getNome());
        m.put("descricao", p.getDescricao());
        m.put("valor", p.getValor());
        m.put("duracaoMeses", p.getDuracaoMeses());
        m.put("valorPorMes", calcularValorPorMes(p));
        BigDecimal economia = calcularEconomia(p);
        m.put("economiaTotal", economia);
        m.put("economiaPercentual", calcularEconomiaPct(p, economia));
        m.put("recomendado", Boolean.TRUE.equals(p.getRecomendado()));
        m.put("aceitaCartao", Boolean.TRUE.equals(p.getAceitaCartao()));
        m.put("aceitaPix", Boolean.TRUE.equals(p.getAceitaPix()));
        m.put("recorrente", p.getDuracaoMeses() != null && p.getDuracaoMeses() == 1);
        m.put("onboardingTipo", p.getOnboardingTipo());
        // featuresJson é string com JSON array — frontend pode parsear se quiser.
        // Pra ficar 100% transparente, devolvemos como string (não desserializa aqui pra
        // evitar conversão a cada request).
        m.put("featuresJson", p.getFeaturesJson());
        return m;
    }

    private BigDecimal calcularValorPorMes(PlanoCatalogo p) {
        if (p.getDuracaoMeses() == null || p.getDuracaoMeses() <= 0) return BigDecimal.ZERO;
        return p.getValor().divide(new BigDecimal(p.getDuracaoMeses()), 2, RoundingMode.HALF_UP);
    }

    /** Economia vs plano "MENSAL" hipotético (preço × meses). 0 se não fizer sentido. */
    private BigDecimal calcularEconomia(PlanoCatalogo p) {
        if (p.getDuracaoMeses() == null || p.getDuracaoMeses() <= 1) return BigDecimal.ZERO;
        PlanoCatalogo mensal = repo.findByCodigoIgnoreCase("MENSAL").orElse(null);
        if (mensal == null) return BigDecimal.ZERO;
        BigDecimal seFosseMensal = mensal.getValor().multiply(new BigDecimal(p.getDuracaoMeses()));
        BigDecimal eco = seFosseMensal.subtract(p.getValor());
        return eco.compareTo(BigDecimal.ZERO) > 0 ? eco : BigDecimal.ZERO;
    }

    private int calcularEconomiaPct(PlanoCatalogo p, BigDecimal economia) {
        if (economia.signum() <= 0) return 0;
        PlanoCatalogo mensal = repo.findByCodigoIgnoreCase("MENSAL").orElse(null);
        if (mensal == null) return 0;
        BigDecimal ref = mensal.getValor().multiply(new BigDecimal(p.getDuracaoMeses()));
        if (ref.signum() <= 0) return 0;
        return economia.multiply(new BigDecimal("100"))
                .divide(ref, 0, RoundingMode.HALF_UP).intValue();
    }

    // ─── BOOT SEED ─────────────────────────────────────────────────────────

    /**
     * CommandLineRunner: garante seed dos 3 planos históricos na primeira vez
     * que o servidor sobe com a tabela vazia. Idempotente — se houver QUALQUER
     * linha, não mexe.
     */
    @Configuration
    @RequiredArgsConstructor
    static class Seeder {
        private final PlanoCatalogoRepository repo;

        @Bean
        CommandLineRunner seedPlanosCatalogo() {
            return args -> {
                if (repo.count() > 0) {
                    log.debug("[PlanoCatalogo] tabela já populada — sem seed");
                    return;
                }
                log.info("[PlanoCatalogo] tabela vazia — inserindo seed dos 3 planos históricos");
                String featuresBasico = "[\"Pedidos online ilimitados\","
                        + "\"Assistente WhatsApp 24/7\","
                        + "\"Cardápio digital + QR Code\","
                        + "\"PIX e cartão integrados\","
                        + "\"Fidelidade + Cupons\","
                        + "\"Relatórios financeiros completos\"]";
                String featuresPadrao = "[\"Pedidos online ilimitados\","
                        + "\"Assistente WhatsApp 24/7\","
                        + "\"Cardápio digital + QR Code\","
                        + "\"PIX e cartão integrados\","
                        + "\"Fidelidade + Cupons\","
                        + "\"Relatórios financeiros completos\","
                        + "\"Suporte prioritário\"]";
                String featuresPremium = "[\"Pedidos online ilimitados\","
                        + "\"Assistente WhatsApp 24/7\","
                        + "\"Cardápio digital + QR Code\","
                        + "\"PIX e cartão integrados\","
                        + "\"Fidelidade + Cupons\","
                        + "\"Relatórios financeiros completos\","
                        + "\"Suporte prioritário\","
                        + "\"Onboarding personalizado\"]";

                repo.save(PlanoCatalogo.builder()
                        .codigo("MENSAL").nome("Mensal")
                        .valor(new BigDecimal("49.90")).duracaoMeses(1)
                        .recomendado(false).aceitaCartao(true).aceitaPix(false)
                        .onboardingTipo("BASICO").featuresJson(featuresBasico)
                        .ativo(true).ordem(1).build());
                repo.save(PlanoCatalogo.builder()
                        .codigo("SEMESTRAL").nome("Semestral")
                        .valor(new BigDecimal("300.00")).duracaoMeses(6)
                        .recomendado(false).aceitaCartao(true).aceitaPix(true)
                        .onboardingTipo("PADRAO").featuresJson(featuresPadrao)
                        .ativo(true).ordem(2).build());
                repo.save(PlanoCatalogo.builder()
                        .codigo("ANUAL").nome("Anual")
                        .valor(new BigDecimal("550.00")).duracaoMeses(12)
                        .recomendado(true).aceitaCartao(true).aceitaPix(true)
                        .onboardingTipo("PREMIUM").featuresJson(featuresPremium)
                        .ativo(true).ordem(3).build());
                log.info("[PlanoCatalogo] seed concluído (3 planos)");
            };
        }
    }
}
