package com.mydelivery.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * Estratégia customizada do Flyway: sempre roda {@code repair()} antes do
 * {@code migrate()}. Racional:
 *
 *   Sem esta estratégia, se uma migration falhou parcialmente em produção
 *   (por exemplo por sintaxe MySQL não suportada, DDL abortado no meio,
 *   deploy killed durante a execução), o Flyway marca a entrada como
 *   "failed" no {@code flyway_schema_history} e RECUSA subir a aplicação
 *   nos próximos deploys — exige intervenção manual (rodar {@code flyway
 *   repair} contra o banco).
 *
 *   Isso deixa o backend caído até alguém logar no Railway/DB. Já vimos
 *   isso acontecer em julho/2026 na V5 (modo_preco em complementos_grupo)
 *   quando {@code IF NOT EXISTS} não foi aceito pelo dialect.
 *
 *   {@code repair()} é seguro: remove ENTRADAS FAILED e realinha o
 *   checksum de migrations aplicadas. Não desfaz alterações no schema
 *   nem exclui migrations que rodaram com sucesso. Efetivamente:
 *   "esqueceu que essa tentativa quebrou, tenta de novo".
 *
 *   O tempo de {@code repair} é insignificante (leitura + no máximo 1 UPDATE
 *   na tabela de histórico). Vale a segurança em troca do overhead.
 */
@Slf4j
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy repairAndMigrate() {
        return flyway -> {
            try {
                flyway.repair();
                log.info("[Flyway] repair OK — histórico realinhado");
            } catch (Exception e) {
                log.warn("[Flyway] repair falhou (segue pro migrate mesmo assim): {}", e.getMessage());
            }
            flyway.migrate();
        };
    }
}
