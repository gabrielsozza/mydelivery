ALTER TABLE restaurantes
    ADD COLUMN rastreamento_entrega_ativo BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN entrega_codigo_confirmacao_ativo BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE pedidos
    ADD COLUMN rastreamento_token_hash CHAR(64) NULL,
    ADD COLUMN rastreamento_token_anterior_hash CHAR(64) NULL,
    ADD COLUMN rastreamento_expira_em DATETIME NULL,
    ADD COLUMN rastreamento_consentido_em DATETIME NULL,
    ADD COLUMN destino_latitude DECIMAL(10,7) NULL,
    ADD COLUMN destino_longitude DECIMAL(10,7) NULL,
    ADD COLUMN entregador_latitude DECIMAL(10,7) NULL,
    ADD COLUMN entregador_longitude DECIMAL(10,7) NULL,
    ADD COLUMN entregador_localizacao_em DATETIME NULL,
    ADD COLUMN entrega_codigo_hash CHAR(64) NULL,
    ADD COLUMN entrega_codigo_expira_em DATETIME NULL,
    ADD COLUMN entrega_codigo_tentativas INT NOT NULL DEFAULT 0,
    ADD COLUMN entrega_codigo_confirmado_em DATETIME NULL,
    ADD COLUMN rastreamento_notificacao_tentada_em DATETIME NULL,
    ADD UNIQUE KEY ux_pedidos_rastreamento_token_hash (rastreamento_token_hash),
    ADD UNIQUE KEY ux_pedidos_rastreamento_token_anterior_hash (rastreamento_token_anterior_hash),
    ADD KEY ix_pedidos_rastreamento_expira (rastreamento_expira_em);

CREATE TABLE entrega_rastreamento_auditoria (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    restaurante_id BIGINT NOT NULL,
    pedido_id BIGINT NOT NULL,
    entregador_id BIGINT NULL,
    evento VARCHAR(40) NOT NULL,
    motivo VARCHAR(500) NULL,
    ator VARCHAR(160) NULL,
    criado_em DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY ix_entrega_auditoria_pedido (restaurante_id, pedido_id, criado_em),
    CONSTRAINT fk_entrega_auditoria_restaurante FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id),
    CONSTRAINT fk_entrega_auditoria_pedido FOREIGN KEY (pedido_id) REFERENCES pedidos(id)
);
