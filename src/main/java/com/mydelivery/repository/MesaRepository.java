package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.Mesa;

public interface MesaRepository extends JpaRepository<Mesa, Long> {

    List<Mesa> findByRestauranteIdOrderByNomeAsc(Long restauranteId);

    Optional<Mesa> findByRestauranteIdAndSlug(Long restauranteId, String slug);

    Optional<Mesa> findByRestauranteSlugAndSlug(String slugRestaurante, String slugMesa);

    boolean existsByRestauranteIdAndSlug(Long restauranteId, String slug);
}
