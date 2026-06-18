package com.mydelivery.model;

import java.math.BigDecimal;

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
    // (na unidade definida abaixo em `unidadeReceita`, ou na do insumo
    //  quando `unidadeReceita` for null — pra retrocompatibilidade).
    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal quantidade;

    /**
     * Unidade da receita — pode ser diferente da unidade do insumo, desde
     * que do mesmo grupo (volume ou massa). Ex: insumo "Açaí" cadastrado
     * em LITROS, receita do produto "Açaí 300ml" usa ML.
     *
     * Quando NULL, o cálculo de viabilidade assume que `quantidade` está na
     * mesma unidade do insumo — comportamento legado, mantém compat com
     * fichas antigas que ainda não foram migradas.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "unidade_receita", length = 10)
    private com.mydelivery.model.Insumo.Unidade unidadeReceita;
}
