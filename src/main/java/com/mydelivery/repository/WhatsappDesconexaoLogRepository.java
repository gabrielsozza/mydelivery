package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mydelivery.model.WhatsappDesconexaoLog;

public interface WhatsappDesconexaoLogRepository extends JpaRepository<WhatsappDesconexaoLog, Long> {

    /** Histórico paginado da instância — usado no endpoint admin. */
    List<WhatsappDesconexaoLog> findByInstanceIdOrderByCriadoEmDesc(Long instanceId, Pageable pageable);

    /** Contagem de eventos por tipo nas últimas 24h — dashboard admin. */
    @Query("SELECT l.tipo, COUNT(l) FROM WhatsappDesconexaoLog l "
         + "WHERE l.criadoEm >= :desde GROUP BY l.tipo")
    List<Object[]> contarPorTipoDesde(@Param("desde") LocalDateTime desde);

    /** Última QUEDA da instância — usado pra mostrar "última queda foi X" no painel. */
    WhatsappDesconexaoLog findFirstByInstanceIdAndTipoOrderByCriadoEmDesc(
            Long instanceId, WhatsappDesconexaoLog.Tipo tipo);
}
