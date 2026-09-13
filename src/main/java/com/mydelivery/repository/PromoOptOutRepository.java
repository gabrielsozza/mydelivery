package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.PromoOptOut;

public interface PromoOptOutRepository extends JpaRepository<PromoOptOut, Long> {

    boolean existsByRestauranteIdAndTelefone(Long restauranteId, String telefone);

    /** Lista telefones em opt-out pra filtragem em bulk (loading da fila da campanha). */
    List<PromoOptOut> findByRestauranteId(Long restauranteId);
}
