package com.mydelivery.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.Restaurante;

public interface RestauranteRepository extends JpaRepository<Restaurante, Long> {
    Optional<Restaurante> findByUsuarioEmail(String email);
    Optional<Restaurante> findBySlug(String slug);
    boolean existsBySlug(String slug);
    boolean existsByCnpj(String cnpj);
}