package com.mydelivery.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cada linha da ficha técnica = relação Produto ↔ Insumo com quantidade.
 * Não criamos uma entidade "FichaTecnica" separada porque a "ficha" de
 * um produto é simplesmente a lista de FichaTecnicaItem onde produto = X.
 *
 * Unique constraint impede o mesmo insumo de aparecer 2× na mesma ficha.
 */
@Entity
@Table(name = "fichas_tecnicas",
    uniqueConstraints = @UniqueConstraint(name = "uk_ficha_produto_insumo", columnNames = {"produto_id", "insumo_id"}),
    indexes = {
        @Index(name = "idx_ficha_produto", columnList = "produto_id"),
        @Index(name = "idx_ficha_insumo", columnList = "insumo_id")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FichaTecnicaItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "produto_id", nullable = false)
    private Produto produto;

    @ManyToOne
    @JoinColumn(name = "insumo_id", nullable = false)
    private Insumo insumo;

    // Quantidade do insumo consumida por 1 unidade do produto
    // (na unidade definida no Insumo — ex: 0.150 KG de carne por hambúrguer)
    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal quantidade;
}
