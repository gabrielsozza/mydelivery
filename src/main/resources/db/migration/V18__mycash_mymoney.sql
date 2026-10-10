CREATE TABLE mycash_config (
 restaurante_id BIGINT PRIMARY KEY, ativo BOOLEAN NOT NULL DEFAULT FALSE,
 atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id)
);
CREATE TABLE mycash_produtos (
 produto_id BIGINT PRIMARY KEY, restaurante_id BIGINT NOT NULL,
 percentual DECIMAL(5,2) NOT NULL DEFAULT 0,
 FOREIGN KEY (produto_id) REFERENCES produtos(id) ON DELETE CASCADE,
 FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id),
 INDEX ix_mycash_produtos_rest(restaurante_id)
);
CREATE TABLE mycash_dispositivos (
 restaurante_id BIGINT NOT NULL, uuid VARCHAR(36) NOT NULL,
 segredo_hash VARCHAR(64) NOT NULL, telefone VARCHAR(15) NOT NULL,
 confiavel BOOLEAN NOT NULL DEFAULT FALSE, conflito BOOLEAN NOT NULL DEFAULT FALSE,
 criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(restaurante_id,uuid), FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id)
);
ALTER TABLE pedidos ADD COLUMN mycash_usado DECIMAL(10,2) NOT NULL DEFAULT 0;
CREATE TABLE mycash_pedidos (
 pedido_id BIGINT PRIMARY KEY, restaurante_id BIGINT NOT NULL, telefone VARCHAR(15) NOT NULL,
 dispositivo_uuid VARCHAR(36), segredo_hash VARCHAR(64), request_key VARCHAR(36) UNIQUE,
 credito DECIMAL(12,2) NOT NULL DEFAULT 0, uso DECIMAL(12,2) NOT NULL DEFAULT 0,
 base_liquida_max DECIMAL(12,2) NOT NULL DEFAULT 0,
 estado_pedido VARCHAR(240), revisao INT NOT NULL DEFAULT 0, criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (pedido_id) REFERENCES pedidos(id), FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id),
 INDEX ix_mycash_pedidos_carteira(restaurante_id,telefone)
);
CREATE TABLE mycash_regras_pedido (
 pedido_id BIGINT NOT NULL, produto_id BIGINT NOT NULL, percentual DECIMAL(5,2) NOT NULL,
 PRIMARY KEY(pedido_id,produto_id), FOREIGN KEY (pedido_id) REFERENCES mycash_pedidos(pedido_id)
);
CREATE TABLE mycash_movimentos (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, restaurante_id BIGINT NOT NULL, telefone VARCHAR(15) NOT NULL,
 pedido_id BIGINT NOT NULL, tipo VARCHAR(16) NOT NULL, valor DECIMAL(12,2) NOT NULL,
 saldo_apos DECIMAL(12,2) NOT NULL, motivo VARCHAR(240) NOT NULL, revisao INT NOT NULL,
 criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_mycash_movimento(pedido_id,tipo,revisao),
 FOREIGN KEY (pedido_id) REFERENCES pedidos(id), FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id),
 INDEX ix_mycash_saldo(restaurante_id,telefone,id)
);
CREATE TABLE mycash_config_historico (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, restaurante_id BIGINT NOT NULL, acao VARCHAR(240) NOT NULL,
 criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id)
);
CREATE TABLE mymoney_config (
 id INT PRIMARY KEY, valor_indicacao DECIMAL(10,2) NOT NULL, atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
INSERT INTO mymoney_config(id,valor_indicacao) VALUES(1,25.00);
CREATE TABLE mymoney_metas (
 id BIGINT PRIMARY KEY, patamar DECIMAL(10,2) NOT NULL UNIQUE, meses INT NOT NULL, pix DECIMAL(10,2) NOT NULL,
 ativo BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO mymoney_metas(id,patamar,meses,pix) VALUES(1,100,1,0),(2,200,1,200),(3,500,3,500);
CREATE TABLE mymoney_participantes (
 restaurante_id BIGINT PRIMARY KEY, codigo VARCHAR(32) NOT NULL UNIQUE,
 pix_chave VARCHAR(180), pix_nome VARCHAR(180), pix_documento VARCHAR(18),
 criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id)
);
CREATE TABLE mymoney_indicacoes (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, indicador_id BIGINT NOT NULL, indicado_id BIGINT NOT NULL UNIQUE,
 codigo VARCHAR(32) NOT NULL, status VARCHAR(24) NOT NULL DEFAULT 'PENDENTE',
 motivo_revisao VARCHAR(240), revisado_por VARCHAR(160), revisado_em DATETIME(6),
 primeiro_pagamento_id BIGINT UNIQUE, valor DECIMAL(10,2) NOT NULL DEFAULT 0,
 validado_em DATETIME(6), criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (indicador_id) REFERENCES restaurantes(id), FOREIGN KEY (indicado_id) REFERENCES restaurantes(id),
 FOREIGN KEY (primeiro_pagamento_id) REFERENCES pagamentos_mensalidade(id), INDEX ix_mymoney_indicador(indicador_id,status)
);
CREATE TABLE mymoney_premios (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, restaurante_id BIGINT NOT NULL, meta_id BIGINT NOT NULL,
 patamar DECIMAL(10,2) NOT NULL, progresso_desbloqueio DECIMAL(10,2) NOT NULL,
 meses INT NOT NULL, pix DECIMAL(10,2) NOT NULL, status VARCHAR(24) NOT NULL,
 meses_aplicados_em DATETIME(6), validade_apos DATETIME(6), cobranca_apos DATETIME(6),
 pix_previsto_em DATE, pix_pago_em DATETIME(6), pix_pago_por VARCHAR(160), pix_comprovante VARCHAR(240),
 pix_chave_snapshot VARCHAR(180), pix_nome_snapshot VARCHAR(180), pix_documento_snapshot VARCHAR(18),
 revisao_necessaria BOOLEAN NOT NULL DEFAULT FALSE, criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_mymoney_meta(restaurante_id,meta_id), FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id),
 FOREIGN KEY (meta_id) REFERENCES mymoney_metas(id)
);
CREATE TABLE mymoney_popup (
 restaurante_id BIGINT NOT NULL, dia DATE NOT NULL, criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(restaurante_id,dia), FOREIGN KEY(restaurante_id) REFERENCES restaurantes(id)
);
CREATE TABLE mymoney_auditoria (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, restaurante_id BIGINT, ator VARCHAR(160) NOT NULL,
 acao VARCHAR(100) NOT NULL, detalhe TEXT NOT NULL, criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
ALTER TABLE assinaturas ADD COLUMN beneficio_vigente_ate DATETIME(6);
