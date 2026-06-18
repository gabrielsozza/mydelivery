package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.GrupoComplementoModelo;

public interface GrupoComplementoModeloRepository
        extends JpaRepository<GrupoComplementoModelo, Long> {

    /** Lista todos os modelos do restaurante, ordenados pelo nome. */
    List<GrupoComplementoModelo> findByRestauranteIdOrderByNomeAsc(Long restauranteId);

    /** Match por nome normalizado — usado em upsert (substitui se já existe). */
    Optional<GrupoComplementoModelo> findByRestauranteIdAndNomeNormalizado(
            Long restauranteId, String nomeNormalizado);
}
