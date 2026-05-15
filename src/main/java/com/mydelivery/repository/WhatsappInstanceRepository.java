package com.mydelivery.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.WhatsappInstance;

public interface WhatsappInstanceRepository extends JpaRepository<WhatsappInstance, Long> {

    Optional<WhatsappInstance> findByRestauranteId(Long restauranteId);

    Optional<WhatsappInstance> findByInstanceName(String instanceName);
}
