CREATE TABLE IF NOT EXISTS restaurant_meta_tracking (
    restaurante_id BIGINT PRIMARY KEY,
    pixel_id VARCHAR(30) NOT NULL,
    token_encrypted TEXT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_restaurant_meta_tracking_restaurante FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id)
);
CREATE TABLE IF NOT EXISTS restaurant_meta_purchase_events (
    restaurante_id BIGINT NOT NULL,
    pedido_id BIGINT NOT NULL,
    event_id VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (restaurante_id, pedido_id),
    CONSTRAINT fk_restaurant_meta_purchase_restaurante FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id),
    CONSTRAINT fk_restaurant_meta_purchase_pedido FOREIGN KEY (pedido_id) REFERENCES pedidos(id)
);
