package com.mydelivery.fiscal.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import com.mydelivery.model.Restaurante;

import jakarta.persistence.*;
import lombok.*;

/**
 * Categoria tributária = grupo de produtos que compartilham NCM/CFOP/CSOSN.
 * O dono cria uma vez ("Águas", "Cervejas", "Refrigerantes"…) e arrasta os
 * produtos do cardápio pra dentro — dispensa configurar fiscal produto a
 * produto. A emissão da NFC-e continua lendo {@code PerfilFiscalProduto};
 * mudanças aqui propagam pros perfis vinculados via
 * {@code CategoriaTributariaService.vincularProdutos()}.
 *
 * <p>Ligada ao restaurante — cada loja tem suas próprias categorias, mesmo
 * que a maioria comece com o mesmo seed padrão.
 */
@Entity
@Table(name = "categoria_tributaria")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoriaTributaria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(nullable = false, length = 120)
    private String nome;

    /** CFOP — código fiscal da operação. 5102 = venda de mercadoria de terceiros. */
    @Column(nullable = false, length = 4)
    private String cfop;

    /** NCM — 8 dígitos. Padrão preparações alimentícias: 21069090. */
    @Column(nullable = false, length = 10)
    private String ncm;

    /** CEST — 7 dígitos, opcional pra maioria (só produtos ST). */
    @Column(length = 10)
    private String cest;

    /** Origem da mercadoria (0=Nacional). */
    @Column(nullable = false)
    @Builder.Default
    private Integer origem = 0;

    /** CSOSN — Simples Nacional. 102 = tributada sem crédito. */
    @Column(name = "csosn_sn", length = 4)
    @Builder.Default
    private String csosnSN = "102";

    /** CST — Regime Normal. 00 = tributada integralmente. Opcional. */
    @Column(name = "cst_normal", length = 4)
    private String cstNormal;

    @Column(name = "aliquota_icms", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal aliquotaIcms = BigDecimal.ZERO;

    @Column(name = "aliquota_pis", precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal aliquotaPis = BigDecimal.ZERO;

    @Column(name = "aliquota_cofins", precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal aliquotaCofins = BigDecimal.ZERO;

    /**
     * Categoria semente (padrão MyDelivery) — não permite excluir, só editar.
     * Ativado no seed inicial pra 5 categorias pré-criadas ao usar pela 1ª vez.
     */
    @Column(name = "semente", nullable = false)
    @Builder.Default
    private Boolean semente = false;

    /**
     * Produtos vinculados — usados pela tela pra listar/desassociar.
     * Como Produto já existe intocado, o vínculo vive numa tabela separada.
     */
    @ManyToMany
    @JoinTable(
        name = "categoria_tributaria_produto",
        joinColumns = @JoinColumn(name = "categoria_tributaria_id"),
        inverseJoinColumns = @JoinColumn(name = "produto_id"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"produto_id"})   // um produto vive em 1 categoria só
    )
    @Builder.Default
    private Set<com.mydelivery.model.Produto> produtos = new HashSet<>();

    @Column(name = "criado_em", updatable = false, insertable = false)
    private LocalDateTime criadoEm;

    @Column(name = "atualizado_em", insertable = false, updatable = false)
    private LocalDateTime atualizadoEm;
}
