package com.mydelivery.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "complementos_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplementoItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "grupo_id", nullable = false)
    private ComplementoGrupo grupo;

    @Column(nullable = false)
    private String nome;

    /**
     * Descrição opcional exibida no cardápio abaixo do nome. Usada pra
     * ingredientes/sabor (ex: pizza "Calabresa" → "calabresa, queijo,
     * cebola, orégano"). NULL/vazio = card mostra só o nome.
     */
    @Column(length = 300)
    private String descricao;

    @Column(precision = 10, scale = 2)
    private BigDecimal precoAdicional = BigDecimal.ZERO;

    /**
     * Quantas vezes este item específico pode ser selecionado dentro do
     * grupo. Default null = comportamento antigo (limite pelo max do grupo).
     * Ex: açaí morango → max 2x (mesmo se o grupo permita 5 no total).
     * Interpretação no frontend: cliente pode clicar "+" até atingir esse
     * limite, aí desabilita.
     */
    @Column(name = "max_selecoes")
    private Integer maxSelecoes;

    private Boolean ativo = true;

    /**
     * Se true, item aparece na tela "Cardápio do dia" pro dono controlar
     * rápido — pensado pra marmitex/PF onde carne/guarnição/salada mudam
     * frequente ("hoje tem moqueca, amanhã não"). Restaurantes que não
     * marcam nenhum item como variável nem veem essa tela.
     *
     * <p>Não afeta o cardápio público diretamente — quem controla
     * visibilidade continua sendo {@code ativo}. A tela "Cardápio do dia"
     * apenas dá uma UI otimizada pra alternar {@code ativo} em lote.
     */
    // nullable pra Hibernate ddl-auto=update conseguir adicionar em prod
    // (NOT NULL em tabela populada quebra). Tratamos null como false no code.
    private Boolean variavel = false;
}