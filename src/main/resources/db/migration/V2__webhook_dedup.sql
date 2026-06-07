-- ============================================================
-- V2 — Tabela de dedup de webhooks externos
-- ============================================================
-- Idempotência: antes de processar webhook do Mercado Pago ou
-- Evolution, fazemos INSERT desta tabela. UNIQUE em (origem,
-- id_externo) bloqueia reentrancia, evitando marcar pedido pago
-- 2x ou somar pontos de fidelidade duplicados.
--
-- ddl-auto=update do Hibernate cria a tabela tambem (model
-- @Entity), mas adicionamos aqui via Flyway pra registrar a
-- evolucao do schema. Ambos chegam no mesmo resultado idempotente
-- (CREATE TABLE IF NOT EXISTS).
-- ============================================================

CREATE TABLE IF NOT EXISTS webhook_eventos_processados (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    origem          VARCHAR(30)  NOT NULL,
    id_externo      VARCHAR(200) NOT NULL,
    processado_em   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_webhook_origem_id (origem, id_externo),
    KEY ix_webhook_processado_em (processado_em)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
