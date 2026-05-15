package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.mydelivery.model.Pedido;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {
    List<Pedido> findByRestauranteIdOrderByCriadoEmDesc(Long restauranteId);

    List<Pedido> findByRestauranteIdAndStatusOrderByCriadoEmDesc(
            Long restauranteId, Pedido.Status status);

    @Query("SELECT p FROM Pedido p WHERE p.restaurante.id = :restauranteId " +
           "AND p.criadoEm BETWEEN :inicio AND :fim ORDER BY p.criadoEm DESC")
    List<Pedido> findByRestauranteIdAndPeriodo(
            Long restauranteId, LocalDateTime inicio, LocalDateTime fim);
}