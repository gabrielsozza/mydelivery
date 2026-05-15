package com.mydelivery.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "complementos_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplementoItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "grupo_id", nullable = false)
    private ComplementoGrupo grupo;

    @Column(nullable = false)
    private String nome;

    @Column(precision = 10, scale = 2)
    private BigDecimal precoAdicional = BigDecimal.ZERO;

    private Boolean ativo = true;
}