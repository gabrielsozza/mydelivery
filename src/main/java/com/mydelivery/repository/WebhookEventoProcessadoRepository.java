package com.mydelivery.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.WebhookEventoProcessado;

public interface WebhookEventoProcessadoRepository
        extends JpaRepository<WebhookEventoProcessado, Long> {

    boolean existsByOrigemAndIdExterno(String origem, String idExterno);
}
