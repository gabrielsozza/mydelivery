package com.mydelivery.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Idempotência de webhooks externos.
 *
 * <p>Mercado Pago, Evolution e qualquer outro provedor podem reenviar o
 * mesmo webhook (timeouts, retries internos). Sem dedup, o backend
 * processava 2x — pedido marcado pago duas vezes, fidelidade somava
 * pontos duplicados, etc.
 *
 * <p>Antes de processar, o handler chama {@code WebhookDedupService.tryClaim
 * (origem, idExterno)}. Se essa origem+id já existir, retorna false e o
 * handler devolve 200 OK sem reprocessar.
 *
 * <p>O {@code idExterno} é o identificador único do evento dado pelo
 * provedor: {@code x-request-id} no MP, {@code event+instanceName+timestamp}
 * na Evolution.
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
    name = "webhook_eventos_processados",
    uniqueConstraints = @UniqueConstraint(
        name = "ux_webhook_origem_id",
        columnNames = { "origem", "id_externo" }
    ),
    indexes = @Index(name = "ix_webhook_processado_em", columnList = "processado_em")
)
public class WebhookEventoProcessado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String origem;

    @Column(name = "id_externo", nullable = false, length = 200)
    private String idExterno;

    @CreationTimestamp
    @Column(name = "processado_em", nullable = false, updatable = false)
    private LocalDateTime processadoEm;
}
