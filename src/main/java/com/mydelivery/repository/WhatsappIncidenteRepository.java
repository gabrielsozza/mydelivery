package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.WhatsappIncidente;

public interface WhatsappIncidenteRepository extends JpaRepository<WhatsappIncidente, Long> {

    /** Para idempotência: já existe incidente aberto desse {tipo, instância}? */
    Optional<WhatsappIncidente> findFirstByInstanceIdAndTipoAndResolvidoEmIsNull(
            Long instanceId, WhatsappIncidente.Tipo tipo);

    /** Todos os abertos (não-resolvidos), do mais recente pro mais antigo. */
    List<WhatsappIncidente> findByResolvidoEmIsNullOrderByAbertoEmDesc();

    /** Abertos NÃO acked — base pro "fila de alertas". */
    List<WhatsappIncidente> findByResolvidoEmIsNullAndAckEmIsNullOrderByAbertoEmDesc();

    /** Histórico recente (resolvido OU aberto) pra aba Monitoramento. */
    List<WhatsappIncidente> findTop100ByOrderByAbertoEmDesc();

    /** Abertos pra uma instância específica. */
    List<WhatsappIncidente> findByInstanceIdAndResolvidoEmIsNullOrderByAbertoEmDesc(Long instanceId);

    /** Abertos pra um restaurante específico (usado pelo banner do painel). */
    List<WhatsappIncidente> findByRestauranteIdAndResolvidoEmIsNullOrderByAbertoEmDesc(Long restauranteId);

    /** Limpeza periódica — retenção. */
    void deleteByAbertoEmBefore(LocalDateTime corte);

    /** Limpa em massa quando vamos deletar a instância (FK protection). */
    void deleteByInstanceId(Long instanceId);
}
