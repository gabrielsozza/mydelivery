-- ============================================================
-- V4 — Pagamentos por pessoa na sessão de mesa
-- ============================================================
-- Quando o garçom fecha a conta, agora ele pode:
--   - Dividir entre N pessoas
--   - Registrar forma de pagamento POR PESSOA
--   - O sistema persiste isso pra relatório/historico
--
-- Colunas novas:
--   pagamentos_json TEXT NULL — JSON do payload de fechamento
--                              ex: {"comServico":true,"divisao":[
--                                    {"pessoa":1,"total":52.5,"formaPagamento":"PIX"},
--                                    {"pessoa":2,"total":52.5,"formaPagamento":"CARTAO_CREDITO"}
--                                  ]}
--   valor_cobrado DECIMAL(10,2) NULL — total efetivamente cobrado
--                                      (com ou sem 10% de servico).
--                                      Diferente de total_acumulado, que so soma
--                                      subtotais brutos dos pedidos.
--
-- Ambas nullable: sessoes antigas (ja fechadas) e fechamentos "simples"
-- (sem divisao) ficam com NULL — comportamento compativel com o anterior.
-- ============================================================

DROP PROCEDURE IF EXISTS add_column_if_not_exists;
DELIMITER $$
CREATE PROCEDURE add_column_if_not_exists(
    IN tbl VARCHAR(64),
    IN col VARCHAR(64),
    IN definition VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name   = tbl
          AND column_name  = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE ', tbl, ' ADD COLUMN ', col, ' ', definition);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$
DELIMITER ;

CALL add_column_if_not_exists('mesa_sessoes', 'pagamentos_json', 'TEXT NULL');
CALL add_column_if_not_exists('mesa_sessoes', 'valor_cobrado',   'DECIMAL(10,2) NULL');

DROP PROCEDURE IF EXISTS add_column_if_not_exists;
