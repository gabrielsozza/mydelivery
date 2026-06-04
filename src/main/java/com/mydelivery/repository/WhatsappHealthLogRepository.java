package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.WhatsappHealthLog;

public interface WhatsappHealthLogRepository extends JpaRepository<WhatsappHealthLog, Long> {

    /** Últimos snapshots de saúde de uma instância, ordenados do mais recente
     *  pro mais antigo. Usado pelo gráfico de acompanhamento no admin. */
    List<WhatsappHealthLog> findByInstanceIdAndEmGreaterThanEqualOrderByEmAsc(
            Long instanceId, LocalDateTime desde);

    /** Limpa snapshots antigos pra controlar tamanho da tabela. */
    void deleteByEmBefore(LocalDateTime corte);
}
