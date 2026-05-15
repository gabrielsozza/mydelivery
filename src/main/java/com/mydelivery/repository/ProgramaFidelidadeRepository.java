package com.mydelivery.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.ProgramaFidelidade;

public interface ProgramaFidelidadeRepository extends JpaRepository<ProgramaFidelidade, Long> {

    Optional<ProgramaFidelidade> findByRestauranteId(Long restauranteId);

    Optional<ProgramaFidelidade> findByRestauranteSlug(String slug);
}
