package com.mydelivery.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.MercadoPagoEventoProcessado;

public interface MercadoPagoEventoProcessadoRepository
        extends JpaRepository<MercadoPagoEventoProcessado, String> {
    // existsById(eventId) + save() é tudo que precisamos
}
