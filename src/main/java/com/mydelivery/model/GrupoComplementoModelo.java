package com.mydelivery.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
 * Template/modelo de grupo de complementos salvo a nível de restaurante,
 * pronto pra ser reaplicado em vários produtos sem recadastrar.
 *
 * Substitui o storage local que ficava no localStorage do browser
 * (myd_grupos_salvos_*) — agora persiste no banco, então grupos salvos
 * NÃO somem mais ao trocar de dispositivo, navegador ou limpar cache.
 *
 * Itens são armazenados como JSON em coluna TEXT (mesmo formato do
 * frontend antigo: [{nome, precoAdicional}, ...]). Evita uma 2ª tabela
 * pra algo que sempre é lido/escrito junto e raramente é consultado
 * por item individual.
 *
 * Unique constraint (restaurante_id, nome_normalizado) impede duplicatas
 * — se o dono salvar "Adicionais de Açaí" 2x, sobrescreve.
 */
@Entity
@Table(
        name = "grupos_complemento_modelo",
        indexes = {
                @Index(name = "idx_gcm_rest", columnList = "restaurante_id"),
                @Index(name = "idx_gcm_nome_norm", columnList = "restaurante_id, nome_normalizado")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_gcm_rest_nome", columnNames = {"restaurante_id", "nome_normalizado"})
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GrupoComplementoModelo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(nullable = false, length = 120)
    private String nome;

    /**
     * Versão minúscula + sem acento do nome — chave da unique constraint.
     * Mantemos separado pra preservar capitalização original em {@code nome}.
     */
    @Column(name = "nome_normalizado", nullable = false, length = 120)
    private String nomeNormalizado;

    @Builder.Default
    @Column(nullable = false)
    private Boolean obrigatorio = false;

    @Builder.Default
    @Column(name = "min_escolhas", nullable = false)
    private Integer minEscolhas = 0;

    @Builder.Default
    @Column(name = "max_escolhas", nullable = false)
    private Integer maxEscolhas = 1;

    /**
     * Itens serializados como JSON: [{"nome":"Morango","precoAdicional":2.5}, …].
     * TEXT (LONGTEXT no MySQL) — suporta listas grandes sem problema.
     */
    @Column(name = "itens_json", columnDefinition = "TEXT")
    private String itensJson;

    @CreationTimestamp
    private LocalDateTime criadoEm;

    @UpdateTimestamp
    private LocalDateTime atualizadoEm;

    /** Helper estático: normaliza nome pra usar como chave única. */
    public static String normalizar(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase();
    }
}
