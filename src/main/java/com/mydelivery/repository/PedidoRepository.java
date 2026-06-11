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

    /**
     * Comanda da mesa (todos os pedidos ATIVOS — não cancelados/entregues —
     * dessa mesa). Usado tanto pelo cliente final (visão própria filtrada
     * por nome) quanto pelo painel (visão geral por mesa).
     */
    @Query("SELECT p FROM Pedido p WHERE p.mesa.id = :mesaId " +
           "AND p.status NOT IN ('CANCELADO', 'ENTREGUE') ORDER BY p.criadoEm ASC")
    List<Pedido> findComandaAtivaPorMesa(Long mesaId);

    /** Todos os pedidos de uma sessão de mesa específica (Garçom). */
    List<Pedido> findBySessaoIdOrderByCriadoEmAsc(Long sessaoId);

    /** Idempotência da integração iFood — evita criar duplicata caso o
     *  polling pegue o mesmo PLC event 2x (acontece se ACK falhar). */
    java.util.Optional<Pedido> findByIfoodOrderId(String ifoodOrderId);
}