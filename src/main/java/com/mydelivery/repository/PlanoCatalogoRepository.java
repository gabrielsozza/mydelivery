package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.PlanoCatalogo;

public interface PlanoCatalogoRepository extends JpaRepository<PlanoCatalogo, Long> {

    List<PlanoCatalogo> findByAtivoTrueOrderByOrdemAscIdAsc();

    List<PlanoCatalogo> findAllByOrderByOrdemAscIdAsc();

    Optional<PlanoCatalogo> findByCodigoIgnoreCase(String codigo);
}
