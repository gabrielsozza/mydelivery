package com.mydelivery.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.Assinatura;

public interface AssinaturaRepository extends JpaRepository<Assinatura, Long> {
    Optional<Assinatura> findByRestauranteId(Long restauranteId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Assinatura a where a.restaurante.id = :restauranteId")
    Optional<Assinatura> findLockedByRestauranteId(@org.springframework.data.repository.query.Param("restauranteId") Long restauranteId);
}