package com.mydelivery.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Migration única de runtime: corrige produtos marcados erradamente como
 * tipo=COMBO quando NÃO são combos de verdade (= não têm filhos em
 * combo_itens).
 *
 * Roda 1x no startup. Idempotente — depois que tudo está NORMAL, o WHERE
 * não encontra nada e a query é no-op (custo ~ms).
 *
 * Bug histórico: em algum momento da fase de Combos, produtos normais
 * ficaram com tipo='COMBO' no banco. Resultado: o painel abria modal de
 * combo ao editar açaí solto, o cardápio público badge-ava tudo como
 * combo, etc. Fix manual via SQL é frágil — automatizamos pro deploy
 * ser autossuficiente.
 *
 * Seguro multi-tenant: o WHERE só toca produtos sem filhos em
 * combo_itens, então combos reais (qualquer restaurante) ficam intactos.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FixProdutoTipoMigration {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void corrigir() {
        try {
            int afetados = jdbc.update(
                    "UPDATE produtos SET tipo = 'NORMAL' " +
                    "WHERE tipo = 'COMBO' " +
                    "  AND id NOT IN (SELECT DISTINCT combo_id FROM combo_itens)");
            if (afetados > 0) {
                log.warn("[FixProdutoTipoMigration] {} produtos restaurados pra tipo=NORMAL (não eram combos de verdade)", afetados);
            } else {
                log.info("[FixProdutoTipoMigration] OK — nenhum produto precisava correção");
            }
        } catch (Exception e) {
            // Best-effort. Se tabela combo_itens ainda não existe (deploy inicial)
            // ou qualquer outra falha, loga e segue — não trava o boot.
            log.warn("[FixProdutoTipoMigration] falha (ignorada): {}", e.getMessage());
        }
    }
}
