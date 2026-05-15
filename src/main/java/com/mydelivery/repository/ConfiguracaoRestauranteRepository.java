package com.mydelivery.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.ConfiguracaoRestaurante;

public interface ConfiguracaoRestauranteRepository
        extends JpaRepository<ConfiguracaoRestaurante, Long> {
    Optional<ConfiguracaoRestaurante> findByRestauranteId(Long restauranteId);
}