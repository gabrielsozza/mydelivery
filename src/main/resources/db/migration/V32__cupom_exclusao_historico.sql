-- Exclusão lógica preserva cupons_usos e o histórico de descontos dos pedidos.
SET @cupom_excluido_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='cupons' AND column_name='excluido_em');
SET @cupom_excluido_sql = IF(@cupom_excluido_exists=0,
 'ALTER TABLE cupons ADD COLUMN excluido_em DATETIME(6) NULL', 'SELECT 1');
PREPARE cupom_excluido_stmt FROM @cupom_excluido_sql;
EXECUTE cupom_excluido_stmt;
DEALLOCATE PREPARE cupom_excluido_stmt;
