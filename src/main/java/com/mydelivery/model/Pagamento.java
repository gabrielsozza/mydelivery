package com.mydelivery.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pagamento de um Pedido — 1:1.
 * Guarda o método escolhido + o BR Code (pra PIX) + status atual.
 *
 * Status:
 *  - PENDENTE: criado, aguardando ação do cliente (ex: pagar PIX)
 *  - APROVADO: confirmado (manual pelo admin, ou via webhook quando integrar gateway)
 *  - RECUSADO: cartão recusado (futuro, com gateway)
 *  - EXPIRADO: PIX não pago no prazo (futuro)
 *  - CANCELADO
 */
@Entity
@Table(name = "pagamentos", indexes = {
        @Index(name = "idx_pagamento_pedido", columnList = "pedido_id"),
        @Index(name = "idx_pagamento_status", columnList = "status"),
        @Index(name = "idx_pagamento_mp_payment", columnList = "mp_payment_id", unique = true),
        @Index(name = "idx_pagamento_mp_idem", columnList = "mp_idempotency_key", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Pagamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "pedido_id", nullable = false, unique = true)
    private Pedido pedido;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Pedido.FormaPagamento metodo;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 15)
    private Status status = Status.PENDENTE;

    // ── PIX ──
    /** "Copia e Cola" PIX (BR Code EMV). Cliente cola no app do banco dele. */
    @Column(name = "pix_br_code", columnDefinition = "TEXT")
    private String pixBrCode;

    /** Chave PIX usada (do restaurante) — guarda pra histórico. */
    @Column(name = "pix_chave", length = 80)
    private String pixChave;

    /** Identificador único da transação (TxID — PIX padrão BR). 25 chars alfanuméricos. */
    @Column(name = "pix_tx_id", length = 25)
    private String pixTxId;

    /** QR Code do MP em base64 (apenas PIX online via gateway). */
    @Column(name = "pix_qr_base64", columnDefinition = "TEXT")
    private String pixQrBase64;

    /** Expiração do QR Code PIX gerado no MP. */
    @Column(name = "expira_em")
    private LocalDateTime expiraEm;

    // ── Cartão (futuro: integração com gateway) ──
    /** ID da transação no gateway externo (Stripe/MP/Asaas). Null por enquanto. */
    @Column(name = "gateway_tx_id", length = 100)
    private String gatewayTxId;

    /** Últimos 4 dígitos do cartão (display only). */
    @Column(name = "cartao_final", length = 4)
    private String cartaoFinal;

    // ── Mercado Pago ──
    /** ID do pagamento no Mercado Pago (resposta do POST /v1/payments). Único. */
    @Column(name = "mp_payment_id")
    private Long mpPaymentId;

    /** Chave de idempotência enviada ao MP — determinística por pedido + método. Única. */
    @Column(name = "mp_idempotency_key", length = 80)
    private String mpIdempotencyKey;

    /** status_detail do MP (ex: "accredited", "cc_rejected_call_for_authorize"). Útil para UI. */
    @Column(name = "mp_status_detail", length = 60)
    private String mpStatusDetail;

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false, nullable = false)
    private LocalDateTime criadoEm;

    @Column(name = "aprovado_em")
    private LocalDateTime aprovadoEm;

    public enum Status {
        PENDENTE, APROVADO, RECUSADO, EXPIRADO, CANCELADO
    }
}
