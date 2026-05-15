package com.mydelivery.job;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Amplia colunas enum cujo tamanho ficou pequeno após valores novos serem
 * adicionados aos enums Java. ddl-auto=update do Hibernate NÃO amplia colunas
 * existentes — só cria/adiciona. Fix manual via ALTER TABLE.
 *
 * Idempotente: lê o tamanho atual da coluna e só altera se for menor que o alvo.
 * Pode rodar em todo startup sem efeito colateral.
 *
 * Caso clássico: enum Pedido.Status ganhou AGUARDANDO_PAGAMENTO (20 chars),
 * mas a coluna foi criada como VARCHAR(11) ou similar.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigracaoEnumLengthJob implements CommandLineRunner {

    private final DataSource dataSource;

    @Override
    public void run(String... args) {
        garantirTamanho("pedidos", "status", 30);
        garantirTamanho("pedidos", "forma_pagamento", 30);
        garantirTamanho("pedidos", "tipo", 20);
    }

    private void garantirTamanho(String tabela, String coluna, int alvo) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Descobre o tamanho atual via information_schema (compatível com MySQL/MariaDB)
            String sql = "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + tabela +
                    "' AND COLUMN_NAME = '" + coluna + "'";
            int atual = -1;
            try (ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) atual = rs.getInt(1);
            }

            if (atual < 0) {
                log.debug("Coluna {}.{} não existe ainda — pulando migração", tabela, coluna);
                return;
            }
            if (atual >= alvo) {
                log.debug("Coluna {}.{} já tem tamanho suficiente ({})", tabela, coluna, atual);
                return;
            }

            log.info("Ampliando {}.{} de VARCHAR({}) pra VARCHAR({})", tabela, coluna, atual, alvo);
            stmt.executeUpdate("ALTER TABLE " + tabela + " MODIFY COLUMN " + coluna + " VARCHAR(" + alvo + ") NOT NULL");
        } catch (Exception e) {
            log.error("Falha ao ampliar coluna {}.{}: {}", tabela, coluna, e.getMessage());
        }
    }
}
