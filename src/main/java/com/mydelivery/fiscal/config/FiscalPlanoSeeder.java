package com.mydelivery.fiscal.config;

import java.math.BigDecimal;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.PlanoCatalogo;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.PlanoCatalogoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Seed idempotente do módulo fiscal:
 *
 * <ol>
 *   <li>Registra 2 planos-catálogo fiscais no {@link PlanoCatalogo}:
 *       {@code FISCAL_MENSAL} (R$ 99,90/mês — 300 notas) e {@code FISCAL_ILIMITADO}
 *       (R$ 129,90/mês — notas ilimitadas). O front lê essa tabela em
 *       {@code /planos.html} pra montar os cards.</li>
 *   <li>Habilita o add-on fiscal ({@code fiscalHabilitado=true}) nas lojas
 *       piloto identificadas pelo slug — hoje só {@code teste} (acesso admin
 *       do dono do MyDelivery), suficiente pra ele testar o fluxo completo
 *       antes de liberar comercialmente.</li>
 * </ol>
 *
 * <p>Roda como {@link ApplicationRunner} — só uma vez no boot, depois de
 * todos os beans prontos. Se o plano já existir ou a loja já estiver com o
 * add-on ligado, é no-op. Falha silenciosamente com log (não trava o app).
 */
@Slf4j
@Configuration
public class FiscalPlanoSeeder {

    private static final String[] SLUGS_PILOTO = { "teste", "mydelivery-teste", "admin" };

    @Bean
    ApplicationRunner semearPlanoFiscal(
            PlanoCatalogoRepository planoRepo,
            RestauranteRepository restauranteRepo) {
        return args -> {
            try {
                semearPlanos(planoRepo);
                habilitarLojasPiloto(restauranteRepo);
            } catch (Exception e) {
                log.warn("[Fiscal][Seed] Falha (segue vida): {}", e.toString());
            }
        };
    }

    @Transactional
    void semearPlanos(PlanoCatalogoRepository repo) {
        criarSeFaltar(repo,
                "FISCAL_MENSAL",
                "Fiscal (NFC-e) — 300 notas",
                "Emissão automática de NFC-e para até 300 pedidos/mês. Inclui certificado A1, contingência offline, envio pro cliente por WhatsApp.",
                new BigDecimal("99.90"),
                "[\"Até 300 NFC-e por mês\","
                        + "\"Emissão automática ao entregar\","
                        + "\"Impressão automática no cupom\","
                        + "\"Envio da nota pro cliente por WhatsApp\","
                        + "\"Contingência offline (funciona mesmo com SEFAZ fora do ar)\","
                        + "\"Relatório mensal pronto pro contador\"]",
                100);
        criarSeFaltar(repo,
                "FISCAL_ILIMITADO",
                "Fiscal (NFC-e) — Ilimitado",
                "Emissão automática de NFC-e sem limite mensal. Ideal pra volumes altos e redes.",
                new BigDecimal("129.90"),
                "[\"NFC-e ilimitadas\","
                        + "\"Emissão automática ao entregar\","
                        + "\"Impressão automática no cupom\","
                        + "\"Envio da nota pro cliente por WhatsApp\","
                        + "\"Contingência offline\","
                        + "\"Multi-CNPJ (várias lojas no mesmo painel)\","
                        + "\"Relatório mensal pronto pro contador\"]",
                101);
    }

    private void criarSeFaltar(PlanoCatalogoRepository repo, String codigo, String nome,
                               String descricao, BigDecimal valor, String featuresJson, int ordem) {
        if (repo.findByCodigoIgnoreCase(codigo).isPresent()) {
            log.debug("[Fiscal][Seed] Plano {} já existe — pulando", codigo);
            return;
        }
        var p = PlanoCatalogo.builder()
                .codigo(codigo)
                .nome(nome)
                .descricao(descricao)
                .valor(valor)
                .duracaoMeses(1)
                .recomendado("FISCAL_MENSAL".equals(codigo))
                .aceitaCartao(true)
                .aceitaPix(true)
                .onboardingTipo("FISCAL")
                .featuresJson(featuresJson)
                .ativo(true)
                .ordem(ordem)
                .build();
        repo.save(p);
        log.info("[Fiscal][Seed] Plano criado: {} R$ {}", codigo, valor);
    }

    @Transactional
    void habilitarLojasPiloto(RestauranteRepository repo) {
        for (String slug : SLUGS_PILOTO) {
            var r = repo.findBySlug(slug).orElse(null);
            if (r == null) continue;
            if (Boolean.TRUE.equals(r.getFiscalHabilitado())) continue;
            r.setFiscalHabilitado(true);
            repo.save(r);
            log.info("[Fiscal][Seed] Add-on fiscal ativado para loja piloto: slug={} id={}",
                    slug, r.getId());
        }
    }
}
