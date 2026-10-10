ALTER TABLE restaurantes ADD COLUMN colaborador_origem_id BIGINT NULL;
CREATE INDEX idx_restaurante_colaborador_origem ON restaurantes(colaborador_origem_id);
