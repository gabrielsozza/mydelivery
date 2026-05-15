package com.mydelivery.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "compra_itens")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompraItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "compra_id", nullable = false)
    @JsonIgnore  // evita ciclo na serialização
    private Compra compra;

    @ManyToOne
    @JoinColumn(name = "insumo_id", nullable = false)
    private Insumo insumo;

    /** Quantidade na unidade do insumo (ex: 5.000 KG). */
    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal quantidade;

    /** Preço por unidade pago nesta compra. */
    @Column(name = "custo_unitario", nullable = false, precision = 14, scale = 4)
    private BigDecimal custoUnitario;

    /** quantidade × custoUnitario. */
    @Column(precision = 14, scale = 2)
    private BigDecimal subtotal;
}
