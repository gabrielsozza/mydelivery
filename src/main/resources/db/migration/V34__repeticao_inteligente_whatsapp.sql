ALTER TABLE restaurantes
    ADD COLUMN repeticao_inteligente_whatsapp BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE pedidos
    ADD COLUMN whatsapp_repeticao_conversa_id BIGINT NULL,
    ADD UNIQUE KEY ux_pedidos_whatsapp_repeticao_conversa (whatsapp_repeticao_conversa_id);

CREATE INDEX ix_clientes_restaurante_telefone ON clientes (restaurante_id, telefone);

CREATE TABLE whatsapp_repeticao_conversas (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    restaurante_id BIGINT NOT NULL,
    telefone_hash CHAR(64) NOT NULL,
    pedido_referencia_id BIGINT NOT NULL,
    estado VARCHAR(32) NOT NULL,
    pagamento_escolhido VARCHAR(32) NULL,
    endereco_confirmado BOOLEAN NOT NULL DEFAULT FALSE,
    pedido_criado_id BIGINT NULL,
    link_token_hash CHAR(64) NULL,
    link_token_expira_em DATETIME NULL,
    ofertado_em DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    aceito_em DATETIME NULL,
    expira_em DATETIME NOT NULL,
    atualizado_em DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY ux_whatsapp_repeticao_origem (restaurante_id, telefone_hash, pedido_referencia_id),
    KEY ix_whatsapp_repeticao_relatorio (restaurante_id, ofertado_em, estado),
    KEY ix_whatsapp_repeticao_ativa (restaurante_id, telefone_hash, expira_em),
    UNIQUE KEY ux_whatsapp_repeticao_token (link_token_hash),
    CONSTRAINT fk_whatsapp_repeticao_restaurante FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id),
    CONSTRAINT fk_whatsapp_repeticao_referencia FOREIGN KEY (pedido_referencia_id) REFERENCES pedidos(id),
    CONSTRAINT fk_whatsapp_repeticao_criado FOREIGN KEY (pedido_criado_id) REFERENCES pedidos(id)
);
