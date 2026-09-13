package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.mydelivery.model.PromoCampanha;

public interface PromoCampanhaRepository extends JpaRepository<PromoCampanha, Long> {

    List<PromoCampanha> findByRestauranteIdOrderByCriadaEmDesc(Long restauranteId);

    /** Uma campanha por restaurante em ENVIANDO/PAUSADA — o service impede
     *  criar outra enquanto a atual não terminar (evita concorrência de disparo). */
    @Query("SELECT c FROM PromoCampanha c WHERE c.restaurante.id = :restauranteId " +
           "AND c.status IN (com.mydelivery.model.PromoCampanha.Status.ENVIANDO, " +
           "                  com.mydelivery.model.PromoCampanha.Status.PAUSADA)")
    Optional<PromoCampanha> findAtivaDoRestaurante(Long restauranteId);

    /** Sender lookup: todas em ENVIANDO, sem filtro por restaurante — o job varre
     *  a fila global e distribui envios respeitando o rate limit de cada instância. */
    List<PromoCampanha> findByStatus(PromoCampanha.Status status);
}
