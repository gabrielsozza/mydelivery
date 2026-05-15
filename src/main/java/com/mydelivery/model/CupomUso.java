package com.mydelivery.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Registra cada uso de cupom — usado para checar limite total e limite por cliente.
 */
@Entity
@Table(name = "cupons_usos", indexes = {
        @Index(name = "idx_cupom_uso_telefone", columnList = "telefone_cliente"),
        @Index(name = "idx_cupom_uso_cupom", columnList = "cupom_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CupomUso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "cupom_id", nullable = false)
    private Cupom cupom;

    @Column(name = "pedido_id")
    private Long pedidoId;

    @Column(name = "telefone_cliente", length = 20)
    private String telefoneCliente;

    @Column(name = "desconto_aplicado", precision = 10, scale = 2)
    private BigDecimal descontoAplicado;

    @CreationTimestamp
    @Column(name = "criado_em", nullable = false, updatable = false)
    private LocalDateTime criadoEm;
}
