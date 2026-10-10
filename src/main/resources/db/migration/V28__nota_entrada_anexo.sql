CREATE TABLE IF NOT EXISTS nota_entrada_anexo (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    restaurante_id BIGINT NOT NULL,
    nome_arquivo VARCHAR(255) NOT NULL,
    tipo_mime VARCHAR(80) NOT NULL,
    tamanho BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    conteudo LONGBLOB NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_nea_rest_sha UNIQUE (restaurante_id, sha256),
    CONSTRAINT fk_nea_rest FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id) ON DELETE CASCADE,
    INDEX idx_nea_rest_data (restaurante_id, criado_em)
);
