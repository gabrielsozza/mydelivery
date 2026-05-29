package com.mydelivery.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Banner promocional do topo do cardápio (estilo iFood/Blendi).
 * Cada banner é uma imagem ordenável, opcionalmente vinculada a um produto.
 * Se o produto for deletado, produto_id vira NULL (banner continua mas sem destino).
 */
@Entity
@Table(name = "banners", indexes = {
    @Index(name = "ix_banner_restaurante_ordem", columnList = "restaurante_id, ordem")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Banner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(name = "imagem_url", nullable = false, length = 500)
    private String imagemUrl;

    /** Produto opcional pra abrir ao clicar. NULL = banner sem destino. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "produto_id")
    private Produto produto;

    @Builder.Default
    @Column(nullable = false)
    private Integer ordem = 0;

    @Builder.Default
    @Column(nullable = false)
    private Boolean ativo = true;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private LocalDateTime criadoEm;

    @PrePersist
    void onCreate() { if (criadoEm == null) criadoEm = LocalDateTime.now(); }
}
