CREATE TABLE whatsapp_bot_primeiro_contato (
    restaurante_id BIGINT NOT NULL,
    telefone VARCHAR(32) NOT NULL,
    criado_em DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (restaurante_id, telefone),
    CONSTRAINT fk_bot_primeiro_contato_restaurante FOREIGN KEY (restaurante_id)
        REFERENCES restaurantes(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
