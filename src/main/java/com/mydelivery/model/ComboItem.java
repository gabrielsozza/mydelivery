package com.mydelivery.model;

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
 * Filho de um produto-combo. Representa "este combo contém 2x Açaí 500ml".
 *
 * Estrutura intencionalmente simples (combo, produtoFilho, quantidade, ordem):
 *
 *  - O combo (Produto com tipo=COMBO) define preço próprio — a soma dos
 *    filhos é ignorada (combo geralmente é mais barato que somar peças).
 *  - Cada filho mantém seus próprios grupos de complementos. No cardápio
 *    público, ao expandir o combo, cliente preenche complementos por filho
 *    separadamente — exatamente como o iFood faz.
 *  - {@code quantidade} > 1 significa "o mesmo produto N vezes" — frontend
 *    renderiza como "#1, #2, …" pra cliente escolher complementos distintos
 *    pra cada repetição.
 *  - {@code ordem} controla apresentação no cardápio e nos cupons impressos.
 */
@Entity
@Table(
        name = "combo_itens",
        indexes = {
                @Index(name = "idx_combo_itens_combo", columnList = "combo_id"),
                @Index(name = "idx_combo_itens_filho", columnList = "produto_filho_id")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComboItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Produto-combo (tipo=COMBO) ao qual este filho pertence. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "combo_id", nullable = false)
    private Produto combo;

    /** Produto interno (normal) que faz parte do combo. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "produto_filho_id", nullable = false)
    private Produto produtoFilho;

    @Builder.Default
    @Column(nullable = false)
    private Integer quantidade = 1;

    @Builder.Default
    @Column(nullable = false)
    private Integer ordem = 0;
}
