package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.Compra;

public interface CompraRepository extends JpaRepository<Compra, Long> {

    List<Compra> findByRestauranteIdOrderByDataCompraDesc(Long restauranteId);

    List<Compra> findByRestauranteIdAndDataCompraBetween(
            Long restauranteId, LocalDateTime ini, LocalDateTime fim);
}
