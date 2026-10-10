CREATE TABLE IF NOT EXISTS whatsapp_atendimento_alertas (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 restaurante_id BIGINT NOT NULL,
 numero VARCHAR(20) NOT NULL,
 nome VARCHAR(255) NULL,
 solicitado_em DATETIME(6) NOT NULL,
 visualizado_em DATETIME(6) NULL,
 INDEX idx_atendimento_pendente (restaurante_id,visualizado_em,solicitado_em)
);
