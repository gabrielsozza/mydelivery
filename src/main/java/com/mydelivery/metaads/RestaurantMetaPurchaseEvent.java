package com.mydelivery.metaads;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "restaurant_meta_purchase_events")
@IdClass(RestaurantMetaPurchaseEvent.Key.class)
@Getter @Setter
public class RestaurantMetaPurchaseEvent {
    @Id @Column(name = "restaurante_id") private Long restauranteId;
    @Id @Column(name = "pedido_id") private Long pedidoId;
    @Column(name = "event_id", nullable = false, length = 100) private String eventId;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    public static class Key implements Serializable {
        public Long restauranteId;
        public Long pedidoId;
        public Key() {}
        public Key(Long restauranteId, Long pedidoId) { this.restauranteId = restauranteId; this.pedidoId = pedidoId; }
        @Override public boolean equals(Object other) { return other instanceof Key key && java.util.Objects.equals(restauranteId, key.restauranteId) && java.util.Objects.equals(pedidoId, key.pedidoId); }
        @Override public int hashCode() { return java.util.Objects.hash(restauranteId, pedidoId); }
    }
}
