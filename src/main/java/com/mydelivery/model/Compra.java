package com.mydelivery.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Registro de uma compra (entrada) de insumos.
 * Cada compra contém múltiplos itens (insumo + quantidade + custo unitário).
 * Ao salvar, cada item dispara uma MovimentacaoEstoque do tipo ENTRADA_COMPRA
 * e atualiza o custo médio ponderado do insumo.
 */
@Entity
@Table(name = "compras", indexes = {
        @Index(name = "idx_compra_restaurante", columnList = "restaurante_id"),
        @Index(name = "idx_compra_data", columnList = "data_compra")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Compra {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(length = 150)
    private String fornecedor;

    @Column(name = "nota_fiscal", length = 80)
    private String notaFiscal;

    /** Quando a mercadoria foi recebida (pode ser diferente de quando registrada). */
    @Column(name = "data_compra", nullable = false)
    private LocalDateTime dataCompra;

    /** Soma de subtotal de todos os itens. Calculado no save. */
    @Column(precision = 14, scale = 2)
    private BigDecimal total;

    @Column(length = 300)
    private String observacao;

    @OneToMany(mappedBy = "compra", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CompraItem> itens = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false, nullable = false)
    private LocalDateTime criadoEm;
}
