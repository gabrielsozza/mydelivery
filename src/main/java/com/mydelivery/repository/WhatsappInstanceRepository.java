package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.WhatsappInstance;

public interface WhatsappInstanceRepository extends JpaRepository<WhatsappInstance, Long> {

    Optional<WhatsappInstance> findByRestauranteId(Long restauranteId);

    Optional<WhatsappInstance> findByInstanceName(String instanceName);

    /**
     * Usado pelo webhook Uazapi: quando o webhook vem com o NÚMERO do WhatsApp
     * como identificador (owner=5527988387661) em vez do nome da instância,
     * fallback pra buscar pela coluna phone. Uazapi manda o número no root
     * do payload em várias situações.
     */
    Optional<WhatsappInstance> findByPhone(String phone);

    /** Usado pelo KeepAliveJob pra pingar Evolution e manter sessao Baileys ativa. */
    List<WhatsappInstance> findAllByStatus(WhatsappInstance.Status status);
}
