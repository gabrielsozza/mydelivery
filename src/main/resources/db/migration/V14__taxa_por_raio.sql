-- Módulo de taxa de entrega POR RAIO (jul/2026)
--
-- Modelo dual: retrocompat total. Restaurantes existentes continuam com
-- modo_taxa='BAIRRO' (default) e usam a tabela taxas_bairro como sempre.
-- Quem migrar pra RAIO ativa o campo e configura zonas circulares.
--
-- Coordenadas do restaurante ficam em endereco_latitude/longitude.
-- Sem coordenadas o modo RAIO não funciona — front força o dono a
-- posicionar o pin no mapa antes de ativar.

ALTER TABLE restaurantes
    ADD COLUMN endereco_latitude  DECIMAL(10,7) NULL,
    ADD COLUMN endereco_longitude DECIMAL(10,7) NULL,
    ADD COLUMN modo_taxa          VARCHAR(10) NOT NULL DEFAULT 'BAIRRO';

CREATE TABLE zonas_entrega (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurante_id BIGINT NOT NULL,
    raio_km        DECIMAL(6,2) NOT NULL,
    taxa           DECIMAL(10,2) NOT NULL,
    ordem          INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_zona_restaurante FOREIGN KEY (restaurante_id)
        REFERENCES restaurantes(id) ON DELETE CASCADE,
    INDEX idx_zona_rest_ordem (restaurante_id, ordem)
);
