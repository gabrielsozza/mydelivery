ALTER TABLE produtos
  ADD COLUMN preco_pix DECIMAL(10,2) NULL,
  ADD COLUMN preco_dinheiro DECIMAL(10,2) NULL,
  ADD COLUMN preco_debito DECIMAL(10,2) NULL,
  ADD COLUMN preco_credito DECIMAL(10,2) NULL;
