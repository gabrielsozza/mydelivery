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
@Table(name = "produtos")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Produto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @ManyToOne
    @JoinColumn(name = "categoria_id")
    private Categoria categoria;

    @Column(nullable = false)
    private String nome;

    @Column(columnDefinition = "TEXT")
    private String descricao;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal preco;

    @Column(precision = 10, scale = 2)
    private BigDecimal precoOriginal;

    private String fotoUrl;

    @Builder.Default
    @Column(nullable = false)
    private Boolean disponivel = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean destaque = false;

    @Builder.Default
    private Integer ordem = 0;

    /**
     * Tipo de produto:
     *  - NORMAL: produto padrão com seus próprios grupos de complementos.
     *  - COMBO:  produto composto de outros produtos (filhos via ComboItem).
     *            Ignora os próprios grupos de complementos — quem manda
     *            são os grupos de cada filho do combo.
     *
     * Default NORMAL pra retrocompatibilidade (produtos antigos não mudam).
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Tipo tipo = Tipo.NORMAL;

    public enum Tipo { NORMAL, COMBO }

    @CreationTimestamp
    private LocalDateTime criadoEm;
}