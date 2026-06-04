package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.PushSubscription;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    List<PushSubscription> findByRestauranteId(Long restauranteId);

    Optional<PushSubscription> findByRestauranteIdAndEndpoint(Long restauranteId, String endpoint);

    void deleteByEndpoint(String endpoint);
}
