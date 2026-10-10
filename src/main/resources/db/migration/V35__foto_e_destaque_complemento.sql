-- Foto opcional e destaque manual para complementos/sabores. Hibernate pode
-- ter criado as colunas antes do Flyway; as verificações deixam a migração segura.
SET @foto_complemento_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='complementos_item' AND column_name='foto_url');
SET @foto_complemento_sql = IF(@foto_complemento_exists=0,
 'ALTER TABLE complementos_item ADD COLUMN foto_url VARCHAR(1000) NULL', 'SELECT 1');
PREPARE foto_complemento_stmt FROM @foto_complemento_sql;
EXECUTE foto_complemento_stmt;
DEALLOCATE PREPARE foto_complemento_stmt;

SET @mais_pedido_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='complementos_item' AND column_name='mais_pedido');
SET @mais_pedido_sql = IF(@mais_pedido_exists=0,
 'ALTER TABLE complementos_item ADD COLUMN mais_pedido BOOLEAN NULL DEFAULT FALSE', 'SELECT 1');
PREPARE mais_pedido_stmt FROM @mais_pedido_sql;
EXECUTE mais_pedido_stmt;
DEALLOCATE PREPARE mais_pedido_stmt;

UPDATE complementos_item SET mais_pedido = FALSE WHERE mais_pedido IS NULL;

SET @modo_preco_modelo_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='grupos_complemento_modelo' AND column_name='modo_preco');
SET @modo_preco_modelo_sql = IF(@modo_preco_modelo_exists=0,
 'ALTER TABLE grupos_complemento_modelo ADD COLUMN modo_preco VARCHAR(10) NULL DEFAULT ''SOMA''', 'SELECT 1');
PREPARE modo_preco_modelo_stmt FROM @modo_preco_modelo_sql;
EXECUTE modo_preco_modelo_stmt;
DEALLOCATE PREPARE modo_preco_modelo_stmt;

UPDATE grupos_complemento_modelo SET modo_preco = 'SOMA' WHERE modo_preco IS NULL;
