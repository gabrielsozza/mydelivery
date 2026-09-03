package com.mydelivery.fiscal.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.fiscal.model.CategoriaTributaria;

public interface CategoriaTributariaRepository extends JpaRepository<CategoriaTributaria, Long> {

    List<CategoriaTributaria> findByRestauranteIdOrderByNomeAsc(Long restauranteId);

    Optional<CategoriaTributaria> findByRestauranteIdAndNome(Long restauranteId, String nome);

    long countByRestauranteId(Long restauranteId);
}
