-- Preserva números com bloco/casa e endereços completos, inclusive de clientes recorrentes.
ALTER TABLE clientes MODIFY COLUMN endereco_numero VARCHAR(120) NULL;
ALTER TABLE pedidos MODIFY COLUMN endereco_entrega VARCHAR(1200) NULL;
