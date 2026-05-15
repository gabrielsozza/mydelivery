package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.SuporteTicket;

public interface SuporteTicketRepository extends JpaRepository<SuporteTicket, Long> {

    /** Lista tickets do restaurante ordenados pelos mais recentes. */
    List<SuporteTicket> findByRestauranteIdOrderByAtualizadoEmDesc(Long restauranteId);

    /** Lookup com tenant — usado em todo endpoint pra evitar IDOR (acesso a ticket de outro). */
    Optional<SuporteTicket> findByIdAndRestauranteId(Long id, Long restauranteId);
}
