package com.mydelivery.fiscal.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.fiscal.model.PerfilFiscalRestaurante;

public interface PerfilFiscalRestauranteRepository
        extends JpaRepository<PerfilFiscalRestaurante, Long> {
    Optional<PerfilFiscalRestaurante> findByRestauranteId(Long restauranteId);
    Optional<PerfilFiscalRestaurante> findByCnpj(String cnpj);
}
