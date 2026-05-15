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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Audit trail de TODAS as alterações de saldo de insumo. Cada mudança no
 * `Insumo.saldoAtual` deve gerar uma linha aqui, com a quantidade (positiva
 * pra entrada, negativa pra saída) e o tipo.
 *
 * Permite rastreabilidade total: "por que esse insumo está com saldo X?"
 *
 * (Fase 2 vai adicionar Compras e Perdas como tipos próprios; pra Fase 1
 * cobrimos os 3 essenciais: AJUSTE manual, SAIDA_VENDA automática, ENTRADA_INICIAL.)
 */
@Entity
@Table(name = "movimentacoes_estoque", indexes = {
        @Index(name = "idx_mov_insumo", columnList = "insumo_id"),
        @Index(name = "idx_mov_data", columnList = "criado_em")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovimentacaoEstoque {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "insumo_id", nullable = false)
    private Insumo insumo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Tipo tipo;

    // Positiva = entrada (aumenta saldo); negativa = saída (diminui saldo).
    // Quem chama é responsável por usar o sinal correto.
    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal quantidade;

    // Saldo do insumo APÓS aplicar essa movimentação (snapshot pra auditoria)
    @Column(name = "saldo_apos", precision = 14, scale = 4, nullable = false)
    private BigDecimal saldoApos;

    // Pedido relacionado (quando tipo = SAIDA_VENDA)
    @Column(name = "pedido_id")
    private Long pedidoId;

    // Compra relacionada (quando tipo = ENTRADA_COMPRA)
    @Column(name = "compra_id")
    private Long compraId;

    @Column(length = 300)
    private String observacao;

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false, nullable = false)
    private LocalDateTime criadoEm;

    /** Categoria adicional pra perdas — facilita relatório por motivo. */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "motivo_perda", length = 30)
    private MotivoPerda motivoPerda;

    public enum Tipo {
        ENTRADA_INICIAL,     // saldo inicial cadastrado manualmente
        ENTRADA_COMPRA,      // entrada via registro de compra (com custo)
        AJUSTE,              // correção manual (positivo ou negativo)
        PERDA,               // saída por desperdício (com motivo categorizado)
        SAIDA_VENDA,         // baixa automática por pedido
        ENTRADA_REVERSAO     // estorno (ex: pedido cancelado)
    }

    public enum MotivoPerda {
        VENCIMENTO, QUEBRA, CONTAGEM, OUTRO
    }
}
