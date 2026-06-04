package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.WhatsappAcaoAutomatica;

public interface WhatsappAcaoAutomaticaRepository extends JpaRepository<WhatsappAcaoAutomatica, Long> {

    List<WhatsappAcaoAutomatica> findByIncidenteIdOrderByEmAsc(Long incidenteId);

    List<WhatsappAcaoAutomatica> findTop100ByOrderByEmDesc();

    List<WhatsappAcaoAutomatica> findByInstanceIdOrderByEmDesc(Long instanceId);

    /** Limpeza periódica — mesma política do health log (30 dias). */
    void deleteByEmBefore(LocalDateTime corte);

    /** Limpa em massa quando vamos deletar a instância (FK protection). */
    void deleteByInstanceId(Long instanceId);
}
