package com.mydelivery.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Catálogo de planos comerciais EDITÁVEL.
 *
 * Antes era um enum hardcoded em {@code Plano}. Migramos pra tabela pra que o
 * admin possa editar valores/benefícios sem redeploy. O enum {@link Plano}
 * continua existindo (compatibilidade com Assinaturas já criadas), mas a
 * exibição/cobrança nova lê DESTA tabela.
 *
 * codigo é a chave de negócio (MENSAL/SEMESTRAL/ANUAL/...) — mantém compat
 * com o enum existente. Novos planos podem ter qualquer código.
 */
@Entity
@Table(name = "planos_catalog",
       uniqueConstraints = @UniqueConstraint(name = "uk_planos_catalog_codigo", columnNames = "codigo"),
       indexes = @Index(name = "idx_planos_catalog_ativo_ordem", columnList = "ativo, ordem"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanoCatalogo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Identificador estável (ex: MENSAL, SEMESTRAL, ANUAL). Maiúsculo, sem espaços. */
    @Column(nullable = false, length = 40)
    private String codigo;

    @Column(nullable = false, length = 80)
    private String nome;

    /** Texto curto que aparece sob o nome no card (opcional). */
    @Column(length = 200)
    private String descricao;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valor;

    @Column(name = "duracao_meses", nullable = false)
    private Integer duracaoMeses;

    @Builder.Default
    @Column(nullable = false)
    private Boolean recomendado = false;

    @Builder.Default
    @Column(name = "aceita_cartao", nullable = false)
    private Boolean aceitaCartao = true;

    @Builder.Default
    @Column(name = "aceita_pix", nullable = false)
    private Boolean aceitaPix = true;

    /** BASICO / PADRAO / PREMIUM — string livre, frontend mapeia. */
    @Column(name = "onboarding_tipo", length = 30)
    private String onboardingTipo;

    /**
     * Lista de benefícios em JSON array (string), ex: ["Pedidos ilimitados","Suporte 24/7"].
     * Mantido como TEXT pra simplificar — frontend faz JSON.parse na hora de exibir.
     */
    @Column(columnDefinition = "TEXT")
    private String featuresJson;

    @Builder.Default
    @Column(nullable = false)
    private Boolean ativo = true;

    @Builder.Default
    @Column(nullable = false)
    private Integer ordem = 0;

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false)
    private LocalDateTime criadoEm;

    @UpdateTimestamp
    @Column(name = "atualizado_em")
    private LocalDateTime atualizadoEm;
}
