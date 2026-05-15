package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.Insumo;

public interface InsumoRepository extends JpaRepository<Insumo, Long> {

    List<Insumo> findByRestauranteIdAndAtivoTrueOrderByNomeAsc(Long restauranteId);

    List<Insumo> findByRestauranteIdOrderByNomeAsc(Long restauranteId);
}
