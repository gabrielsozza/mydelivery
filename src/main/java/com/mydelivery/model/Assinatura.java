package com.mydelivery.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "assinaturas")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Assinatura {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valor;

    /** Plano atual (MENSAL/SEMESTRAL/ANUAL). Null durante o TRIAL. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Plano plano;

    /**
     * Data até a qual a vigência atual está paga.
     * - TRIAL: igual a trialFim
     * - ATIVA: data de fim do período pago (próxima cobrança = renovação)
     */
    private LocalDateTime validaAte;

    private LocalDateTime trialInicio;
    private LocalDateTime trialFim;
    private LocalDateTime proximaCobranca;
    private LocalDateTime ultimaCobranca;
    private LocalDateTime canceladoEm;

    /** PIX ou CARTAO — método escolhido na hora da assinatura. */
    @Column(name = "metodo_pagamento", length = 20)
    private String metodoPagamento;

    /**
     * Referência do gateway (MP) — pra renovação automática.
     * Formato cartão salvo: "trial-card:CUSTOMER_ID:CARD_ID".
     * Formato livre pra outros gateways no futuro.
     */
    @Column(name = "referencia_gateway", length = 200)
    private String referenciaGateway;

    public enum Status {
        TRIAL, ATIVA, PENDENTE, INADIMPLENTE, CANCELADA
    }
}