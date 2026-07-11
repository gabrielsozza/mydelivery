-- Meio a meio pra pizzarias: modo de cálculo do preço quando cliente escolhe
-- vários itens do mesmo grupo de complementos.
--   SOMA   → soma os preços (padrão, retrocompat com extras/adicionais).
--   MAIOR  → cobra apenas o item mais caro (pizza meio a meio: 2 sabores).
-- Default SOMA preserva 100% dos grupos existentes.

ALTER TABLE complementos_grupo
    ADD COLUMN modo_preco VARCHAR(10) NOT NULL DEFAULT 'SOMA';
