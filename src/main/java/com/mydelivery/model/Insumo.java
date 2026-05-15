package com.mydelivery.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
 * Insumo / ingrediente do restaurante. O saldo é mantido em tempo real
 * (atualizado via MovimentacaoEstoque) pra evitar somas pesadas em cada consulta.
 * MovimentacaoEstoque serve como audit trail.
 */
@Entity
@Table(name = "insumos", indexes = {
        @Index(name = "idx_insumo_restaurante", columnList = "restaurante_id"),
        @Index(name = "idx_insumo_nome", columnList = "nome")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Insumo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(nullable = false, length = 100)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Unidade unidade = Unidade.UN;

    // Saldo atual em estoque (na unidade definida acima)
    @Builder.Default
    @Column(name = "saldo_atual", precision = 14, scale = 4, nullable = false)
    private BigDecimal saldoAtual = BigDecimal.ZERO;

    // Limite que dispara alerta (também na mesma unidade)
    @Builder.Default
    @Column(name = "saldo_minimo", precision = 14, scale = 4, nullable = false)
    private BigDecimal saldoMinimo = BigDecimal.ZERO;

    // Custo médio ponderado por unidade. Atualizado em cada compra.
    // (Por enquanto setado manualmente — Fase 2 vai automatizar com Compras)
    @Column(name = "custo_medio", precision = 14, scale = 4)
    private BigDecimal custoMedio;

    // Observação livre (ex: "marca preferida X", "comprar no fornecedor Y")
    @Column(length = 300)
    private String observacao;

    @Builder.Default
    @Column(nullable = false)
    private Boolean ativo = true;

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false, nullable = false)
    private LocalDateTime criadoEm;

    @UpdateTimestamp
    @Column(name = "atualizado_em")
    private LocalDateTime atualizadoEm;

    public enum Unidade {
        UN,   // unidade (peça)
        KG,   // quilograma
        G,    // grama
        L,    // litro
        ML    // mililitro
    }
}
