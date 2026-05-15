package com.mydelivery.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Registro de webhooks já processados do Mercado Pago.
 *
 * Idempotência camada 3: o MP reentrega webhooks em caso de timeout/5xx e pode
 * disparar múltiplos eventos pro mesmo payment (created → updated). Antes de
 * processar qualquer webhook, conferimos se o eventId já existe — se sim,
 * respondemos 200 OK sem reprocessar (no-op).
 *
 * eventId = header "x-request-id" enviado pelo MP no webhook (sempre único por evento).
 */
@Entity
@Table(name = "mp_eventos_processados", indexes = {
        @Index(name = "idx_mp_evento_payment", columnList = "payment_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MercadoPagoEventoProcessado {

    /** x-request-id do header do webhook (UUID-like, único por evento). */
    @Id
    @Column(name = "event_id", length = 80)
    private String eventId;

    /** ID do payment no MP que disparou o evento (pra auditoria). */
    @Column(name = "payment_id")
    private Long paymentId;

    /** Tipo do evento (payment, plan, subscription...). */
    @Column(name = "tipo", length = 30)
    private String tipo;

    /** Ação reportada pelo MP (payment.created, payment.updated). */
    @Column(name = "acao", length = 40)
    private String acao;

    @CreationTimestamp
    @Column(name = "processado_em", updatable = false, nullable = false)
    private LocalDateTime processadoEm;
}
