package com.mydelivery.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Hibernate ddl-auto=update NÃO faz ALTER COLUMN pra trocar tipo (ex:
 * VARCHAR(255) → TEXT). Quando aumentamos uma coluna no entity, precisamos
 * forçar o ALTER manualmente.
 *
 * Bug histórico: pedido_itens.observacao era VARCHAR(255). Combos geram
 * obs hierárquica longa ("📦 Combo: X ▸ Açaí 500ml #1 ▸ Cobertura: ... ▸
 * Complementos: ... ▸ Açaí 500ml #2 ▸ ...") que estoura facilmente, e o
 * MySQL devolve "Data truncation: Data too long for column 'observacao'".
 * O DataIntegrityViolationException virava 409 "Conflito de dados" no
 * frontend, e o cliente não conseguia finalizar pedido.
 *
 * Idempotente: cada ALTER usa MODIFY que é no-op se a coluna já está no
 * tipo certo. Erros são logados e ignorados pra não travar o boot.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AmpliarColunasMigration {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void ampliar() {
        // pedido_itens.observacao: VARCHAR(255) → TEXT
        alterar("ALTER TABLE pedido_itens MODIFY observacao TEXT",
                "pedido_itens.observacao → TEXT");
    }

    private void alterar(String sql, String descricao) {
        try {
            jdbc.execute(sql);
            log.info("[AmpliarColunasMigration] OK — {}", descricao);
        } catch (Exception e) {
            // No-op se já estiver no tipo certo, ou se a tabela ainda não
            // existir (deploy inicial). Não trava o boot.
            log.warn("[AmpliarColunasMigration] {}: {}", descricao, e.getMessage());
        }
    }
}
