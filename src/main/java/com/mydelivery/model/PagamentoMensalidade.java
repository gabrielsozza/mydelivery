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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "pagamentos_mensalidade")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PagamentoMensalidade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "metodo_pagamento", length = 20)
    private String metodoPagamento;

    @Column(name = "referencia_gateway", length = 200)
    private String referenciaGateway;

    private LocalDateTime pagoEm;

    /** Plano alvo da cobrança (MENSAL/SEMESTRAL/ANUAL) — null em pagamentos antigos. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Plano plano;

    /**
     * Categoria do erro pra diagnóstico — diferencia CLIENTE (cartão), GATEWAY (MP)
     * ou SISTEMA (bug interno). Null em pagamentos OK.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "categoria_erro", length = 20)
    private CategoriaErro categoriaErro;

    /** Detalhe do erro retornado pelo gateway ou exception interna. */
    @Column(name = "motivo_erro", columnDefinition = "TEXT")
    private String motivoErro;

    /** ID do Payment no MP (pra rastreio cruzado). */
    @Column(name = "mp_payment_id")
    private Long mpPaymentId;

    /** status_detail do MP (ex: cc_rejected_insufficient_amount). */
    @Column(name = "mp_status_detail", length = 80)
    private String mpStatusDetail;

    @CreationTimestamp
    private LocalDateTime criadoEm;

    public enum Status {
        PENDENTE, PAGO, REJEITADO, CANCELADO
    }

    public enum CategoriaErro {
        /** Cartão recusado, saldo insuficiente, dados inválidos — culpa do cliente. */
        CLIENTE,
        /** Mercado Pago retornou erro (5xx, timeout, formato inesperado). */
        GATEWAY,
        /** Bug interno do MyDelivery — exceção não tratada. */
        SISTEMA
    }
}