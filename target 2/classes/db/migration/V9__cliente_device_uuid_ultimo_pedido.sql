-- Feature "Pedir novamente" — identificação de cliente por dispositivo
-- e memória do último pedido pra repetir em 1 clique.
--
-- Modelagem:
--  - device_uuid é escopado por restaurante (unique composto), então cada
--    loja vê APENAS seus próprios clientes — isolamento por design.
--  - Campos cidade/estado/cep completam o endereço estruturado que já existia
--    parcialmente (rua/numero/complemento/bairro).
--  - ultimo_pedido_id é FK opcional (ON DELETE SET NULL) — se o pedido for
--    apagado por qualquer razão, o cliente não vira órfão.
--  - total_pedidos é contador desnormalizado — evita COUNT(*) toda vez que
--    o admin abre a ficha do cliente.

ALTER TABLE clientes
    ADD COLUMN device_uuid VARCHAR(36) NULL AFTER endereco_referencia,
    ADD COLUMN endereco_cidade VARCHAR(120) NULL AFTER endereco_bairro,
    ADD COLUMN endereco_estado VARCHAR(10) NULL AFTER endereco_cidade,
    ADD COLUMN endereco_cep VARCHAR(10) NULL AFTER endereco_estado,
    ADD COLUMN ultimo_pedido_id BIGINT NULL,
    ADD COLUMN ultimo_pedido_em DATETIME NULL,
    ADD COLUMN total_pedidos INT NOT NULL DEFAULT 0;

-- Unique composto (restaurante_id, device_uuid) — NULL não conflita com NULL
-- no MySQL, então clientes antigos sem UUID convivem sem problema.
ALTER TABLE clientes
    ADD CONSTRAINT uk_cliente_rest_device UNIQUE (restaurante_id, device_uuid);

-- FK do último pedido, SET NULL pra não travar exclusão de pedidos.
ALTER TABLE clientes
    ADD CONSTRAINT fk_cliente_ultimo_pedido
    FOREIGN KEY (ultimo_pedido_id) REFERENCES pedidos(id) ON DELETE SET NULL;

CREATE INDEX idx_cliente_ultimo_pedido ON clientes (ultimo_pedido_id);
