package com.mydelivery.model;

import java.math.BigDecimal;

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
@Table(name = "pedido_item_complementos")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PedidoItemComplemento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "pedido_item_id", nullable = false)
    private PedidoItem pedidoItem;

    @ManyToOne
    @JoinColumn(name = "complemento_id", nullable = false)
    private ComplementoItem complemento;

    @Column(precision = 10, scale = 2)
    private BigDecimal precoAdicional;
}