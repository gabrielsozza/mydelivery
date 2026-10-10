CREATE TABLE mymoney_saques (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, restaurante_id BIGINT NOT NULL,
 request_key VARCHAR(36) NOT NULL UNIQUE, valor DECIMAL(12,2) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PIX_PENDENTE',
 pix_chave_snapshot VARCHAR(180) NOT NULL, pix_nome_snapshot VARCHAR(180) NOT NULL,
 pix_documento_snapshot VARCHAR(18) NOT NULL,
 previsto_em DATE, pago_em DATETIME(6), pago_por VARCHAR(160), comprovante VARCHAR(240),
 cancelado_em DATETIME(6), cancelado_por VARCHAR(160), motivo_cancelamento VARCHAR(240),
 criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(restaurante_id) REFERENCES restaurantes(id), INDEX ix_mymoney_saques_loja(restaurante_id,status)
);
CREATE TABLE mymoney_movimentos (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, restaurante_id BIGINT NOT NULL,
 indicacao_id BIGINT, saque_id BIGINT, tipo VARCHAR(32) NOT NULL,
 valor DECIMAL(12,2) NOT NULL, saldo_apos DECIMAL(12,2) NOT NULL,
 motivo VARCHAR(240) NOT NULL, criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_mymoney_credito(indicacao_id,tipo), UNIQUE KEY uq_mymoney_saque_mov(saque_id,tipo),
 FOREIGN KEY(restaurante_id) REFERENCES restaurantes(id),
 FOREIGN KEY(indicacao_id) REFERENCES mymoney_indicacoes(id), FOREIGN KEY(saque_id) REFERENCES mymoney_saques(id),
 INDEX ix_mymoney_movimentos_loja(restaurante_id,id)
);
-- Indicações já validadas passam a ser saldo financeiro, sem exigir qualquer meta.
INSERT INTO mymoney_movimentos(restaurante_id,indicacao_id,tipo,valor,saldo_apos,motivo)
 SELECT indicador_id,id,'CREDITO_INDICACAO',valor,
 SUM(valor) OVER (PARTITION BY indicador_id ORDER BY id ROWS UNBOUNDED PRECEDING),
 'Crédito por indicação validada antes da disponibilização da carteira'
 FROM mymoney_indicacoes WHERE status='VALIDADA';
