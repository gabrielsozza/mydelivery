package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.ZonaEntrega;

public interface ZonaEntregaRepository extends JpaRepository<ZonaEntrega, Long> {

    /** Zonas do restaurante em ordem crescente de raio — a lógica de cálculo
     *  itera até achar a primeira zona cujo raio >= distância do cliente. */
    List<ZonaEntrega> findByRestauranteIdOrderByRaioKmAsc(Long restauranteId);

    void deleteByRestauranteId(Long restauranteId);
}
