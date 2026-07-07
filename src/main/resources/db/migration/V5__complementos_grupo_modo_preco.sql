-- Meio a meio pra pizzarias: modo de cálculo do preço quando cliente escolhe
-- vários itens do mesmo grupo de complementos.
--   SOMA   → soma os preços (padrão, retrocompat com extras/adicionais).
--   MAIOR  → cobra apenas o item mais caro (pizza meio a meio: 2 sabores).
-- Aplica só a grupos onde o dono explicitamente escolher — default SOMA
-- preserva 100% dos grupos existentes sem alteração de comportamento.
--
-- IF NOT EXISTS: em ambientes de dev, `spring.jpa.hibernate.ddl-auto=update`
-- pode ter adicionado a coluna antes desta migração. Idempotente evita quebrar
-- o boot nesses casos. MySQL 8.0.29+ e MariaDB 10.0.2+ suportam.

ALTER TABLE complementos_grupo
    ADD COLUMN IF NOT EXISTS modo_preco VARCHAR(10) NOT NULL DEFAULT 'SOMA';
