-- ============================================================
-- V3 — Indices em pedidos
-- ============================================================
-- Antes desses indices, queries hot do painel (listar pedidos
-- recentes, filtrar por status, comanda de mesa) faziam fullscan
-- da tabela pedidos — em horario de pico isso travava o banco e o
-- painel demorava 5-15s pra listar.
--
-- Indices criados (alinhados com os @Index no model Pedido):
--   ix_pedido_rest_criado          (restaurante_id, criado_em)
--   ix_pedido_rest_status_criado   (restaurante_id, status, criado_em)
--   ix_pedido_mesa_status          (mesa_id, status)
--   ix_pedido_sessao_criado        (sessao_id, criado_em)
--
-- IF NOT EXISTS pra ser idempotente: o ddl-auto do Hibernate
-- tambem cria os mesmos indices a partir das anotacoes @Index,
-- entao essa migration pode rodar depois de o Hibernate ja ter
-- criado, sem quebrar.
-- ============================================================

-- MySQL nao suporta CREATE INDEX IF NOT EXISTS nativo. Usamos
-- procedimento + condicional. Cada bloco verifica information_schema
-- pra so criar se ja nao existir.

DROP PROCEDURE IF EXISTS create_index_if_not_exists;
DELIMITER $$
CREATE PROCEDURE create_index_if_not_exists(
    IN tbl VARCHAR(64),
    IN idx VARCHAR(64),
    IN cols VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name   = tbl
          AND index_name   = idx
    ) THEN
        SET @sql = CONCAT('CREATE INDEX ', idx, ' ON ', tbl, ' (', cols, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$
DELIMITER ;

CALL create_index_if_not_exists('pedidos', 'ix_pedido_rest_criado',          'restaurante_id, criado_em');
CALL create_index_if_not_exists('pedidos', 'ix_pedido_rest_status_criado',   'restaurante_id, status, criado_em');
CALL create_index_if_not_exists('pedidos', 'ix_pedido_mesa_status',          'mesa_id, status');
CALL create_index_if_not_exists('pedidos', 'ix_pedido_sessao_criado',        'sessao_id, criado_em');

DROP PROCEDURE IF EXISTS create_index_if_not_exists;
