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
import jakarta.persistence.UniqueConstraint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Liga um produto-combo a grupos de complementos (templates da Fase 1)
 * que o dono escolheu manualmente.
 *
 * Quando o cliente abre o combo no cardápio, cada SLOT (= filho × repetição)
 * herda esses grupos — em vez de herdar automaticamente os grupos do produto
 * filho como produto solto. Permite ao dono ter combos com regras próprias:
 * "Combo Açaí Família" pode ter só Frutas + Coberturas, mesmo que o "Açaí
 * 500ml" solto tenha 5 grupos diferentes.
 *
 * Fallback retrocompat: se um combo não tem nenhuma linha aqui, o cardápio
 * volta a herdar os grupos do filho (combos antigos não quebram).
 */
@Entity
@Table(
        name = "combo_grupos",
        indexes = {
                @Index(name = "idx_combo_grupos_combo", columnList = "combo_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_combo_grupo", columnNames = {"combo_id", "grupo_modelo_id"})
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComboGrupo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Combo (produto com tipo=COMBO) que recebe esse grupo. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "combo_id", nullable = false)
    private Produto combo;

    /** Template de grupo (vinha como GrupoComplementoModelo) que o dono escolheu. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "grupo_modelo_id", nullable = false)
    private GrupoComplementoModelo grupoModelo;

    @Builder.Default
    @Column(nullable = false)
    private Integer ordem = 0;

    /**
     * JSON com lista de IDs dos produtos-filho do combo aos quais ESTE grupo
     * se aplica. Quando NULL ou vazio → aplica em TODOS os filhos (default).
     * Quando preenchido → aplica APENAS nos produtos cujos IDs estão na lista.
     *
     * Exemplo: combo tem [Açaí 500ml id=403, Açaí 300ml id=402]. Se o grupo
     * "Coberturas premium" tem filhosAplicaveisJson="[403]", esse grupo só
     * aparece nos slots do Açaí 500ml — o Açaí 300ml não recebe.
     *
     * Granularidade por PRODUTO (não por repetição): se combo tem 2× Açaí 500ml,
     * marcar 403 aplica nas DUAS instâncias. Cobre 95% dos casos sem
     * complexidade de slot-tracking.
     *
     * Default null pra retrocompat — combos antigos seguem aplicando todos
     * os grupos em todos os filhos como sempre (comportamento anterior).
     */
    @Column(name = "filhos_aplicaveis_json", columnDefinition = "TEXT")
    private String filhosAplicaveisJson;
}
