CREATE TABLE IF NOT EXISTS restaurant_meta_event_log (
    restaurante_id BIGINT NOT NULL,
    event_id VARCHAR(100) NOT NULL,
    event_name VARCHAR(40) NOT NULL,
    event_source VARCHAR(12) NOT NULL,
    value_amount DECIMAL(12, 2) NULL,
    content_id VARCHAR(100) NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (restaurante_id, event_id),
    INDEX idx_meta_event_restaurant_time (restaurante_id, occurred_at),
    INDEX idx_meta_event_restaurant_name_time (restaurante_id, event_name, occurred_at),
    CONSTRAINT fk_meta_event_restaurante FOREIGN KEY (restaurante_id) REFERENCES restaurantes(id)
);

INSERT IGNORE INTO restaurant_meta_event_log
    (restaurante_id, event_id, event_name, event_source, value_amount, content_id, occurred_at)
SELECT e.restaurante_id, e.event_id, 'Purchase', 'SERVER', p.total,
       CONCAT('order_', e.pedido_id), e.created_at
FROM restaurant_meta_purchase_events e
JOIN pedidos p ON p.id = e.pedido_id;
