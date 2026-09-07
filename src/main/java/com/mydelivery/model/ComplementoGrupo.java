package com.mydelivery.model;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity
@Table(name = "complementos_grupo")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplementoGrupo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "produto_id", nullable = false)
    private Produto produto;

    @Column(nullable = false)
    private String nome;

    private Boolean obrigatorio = false;
    private Integer minEscolhas = 0;
    private Integer maxEscolhas = 1;

    /**
     * Como calcular o preço quando o cliente seleciona vários itens do grupo.
     * SOMA (padrão) → soma o preço de todos os itens escolhidos (extras, adicionais).
     * MAIOR         → cobra apenas o item mais caro (pizza meio a meio: 2 sabores,
     *                 paga o mais caro dos 2). Aplicado por grupo, não por produto.
     * Default SOMA garante retrocompat com todos os grupos existentes.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "modo_preco", length = 10, nullable = false)
    @Builder.Default
    private ModoPreco modoPreco = ModoPreco.SOMA;

    public enum ModoPreco { SOMA, MAIOR }

    /**
     * Se true, aparece uma opção "Sem [nome do grupo]" (ex: "Sem carne")
     * no cardápio como primeira escolha. Útil pra marmita/PF onde cliente
     * pode não querer uma categoria específica. Default false — grupos
     * antigos continuam iguais.
     *
     * <p>Não mexe em min/max: se o grupo é obrigatório com min=1, escolher
     * "Sem X" satisfaz o mínimo. Se não-obrigatório, o botão "Sem X" só
     * economiza o clique de "não escolher nada".
     */
    // nullable porque Hibernate ddl-auto=update NÃO consegue adicionar coluna
    // NOT NULL em tabela com dados sem default. Tratamos null como false no code.
    @Column(name = "permitir_nenhuma")
    @Builder.Default
    private Boolean permitirNenhuma = false;

    /**
     * Se true, o grupo aparece SO 1 VEZ no fluxo do cliente, mesmo que ele
     * compre N unidades do produto. Uso: brinde ("Fini" 1x por pedido de 3
     * acais), personalizacao do pacote em vez de cada item. Se false
     * (padrao), o grupo aparece a cada unidade — comportamento original.
     */
    @Column(name = "uma_vez_por_combo")
    @Builder.Default
    private Boolean umaVezPorCombo = false;

    /** Ordem em que o grupo aparece no cardapio pro cliente. Menor = topo.
     *  Default 0 — grupos antigos mantem ordem por id (fallback). Painel
     *  ajusta via setinhas up/down. */
    @Column(nullable = false)
    @Builder.Default
    private Integer ordem = 0;

    // EAGER porque open-in-view=false: serialização do response fora da transação
    // disparava LazyInitException ao iterar getItens() → endpoint retornava 500
    // e o painel/cardápio ficavam sem complementos mesmo com dados no banco.
    @OneToMany(mappedBy = "grupo", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    private List<ComplementoItem> itens;
}