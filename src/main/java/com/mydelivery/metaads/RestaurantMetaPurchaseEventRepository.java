package com.mydelivery.metaads;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RestaurantMetaPurchaseEventRepository extends JpaRepository<RestaurantMetaPurchaseEvent, RestaurantMetaPurchaseEvent.Key> {
    Optional<RestaurantMetaPurchaseEvent> findByRestauranteIdAndPedidoId(Long restauranteId, Long pedidoId);
}
