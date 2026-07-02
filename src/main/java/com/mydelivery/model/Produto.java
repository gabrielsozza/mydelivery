package com.mydelivery.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "produtos")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Produto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @ManyToOne
    @JoinColumn(name = "categoria_id")
    private Categoria categoria;

    @Column(nullable = false)
    private String nome;

    @Column(columnDefinition = "TEXT")
    private String descricao;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal preco;

    @Column(precision = 10, scale = 2)
    private BigDecimal precoOriginal;

    private String fotoUrl;

    @Builder.Default
    @Column(nullable = false)
    private Boolean disponivel = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean destaque = false;

    @Builder.Default
    private Integer ordem = 0;

    /**
     * Tipo de produto:
     *  - NORMAL: produto padrão com seus próprios grupos de complementos.
     *  - COMBO:  produto composto de outros produtos (filhos via ComboItem).
     *            Ignora os próprios grupos de complementos — quem manda
     *            são os grupos de cada filho do combo.
     *
     * Default NORMAL pra retrocompatibilidade. Coluna nullable porque o
     * Hibernate ddl-auto=update NÃO consegue adicionar NOT NULL em tabela
     * com dados existentes (MySQL rejeita ALTER sem DEFAULT). Tratamos
     * null como NORMAL nos services pra robustez.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Tipo tipo = Tipo.NORMAL;

    public enum Tipo { NORMAL, COMBO }

    /**
     * Quando true, o campo `preco` é apenas REFERENCIAL (vitrine) — usado pra
     * exibição "R$ X/{unidade}" no cardápio. O valor que o cliente paga vem
     * dos complementos (porções com preço real).
     *
     * Caso de uso: feijão tropeiro vendido por kg. Card mostra "R$ 59,99/kg".
     * Cliente seleciona porção (250g=R$15, 500g=R$30, 1kg=R$59,99) via
     * complemento obrigatório, e o checkout só conta o preço da porção.
     *
     * Default false pra retrocompatibilidade — produtos antigos seguem como
     * sempre (preço somado aos complementos).
     */
    // nullable porque Hibernate ddl-auto=update NÃO consegue adicionar coluna
    // NOT NULL em tabela com dados. Tratamos null como false nos services.
    @Builder.Default
    @Column(name = "preco_vitrine")
    private Boolean precoVitrine = false;

    /**
     * Unidade exibida ao lado do preço quando precoVitrine=true.
     * Valores típicos: "kg", "g", "100g", "L", "ml", "un", "porção".
     * Null = sem sufixo de unidade.
     */
    @Column(name = "unidade_preco", length = 16)
    private String unidadePreco;

    /**
     * Se true, o preço é exibido no cardápio como "a partir de R$ X" —
     * útil pra produtos com complementos que somam ao valor final
     * (ex: feijoada base R$ 46, com adicionais chega a R$ 60).
     * Independente do precoVitrine (kg): dá pra usar sozinho.
     * Default false (comportamento antigo — mostra só o preço).
     */
    @Builder.Default
    @Column(name = "preco_a_partir_de")
    private Boolean precoAPartirDe = false;

    /**
     * Dias da semana em que o produto fica ativo no cardápio público.
     * CSV com codes de 3 letras: "SEG,TER,QUA,QUI,SEX,SAB,DOM".
     * NULL ou vazio = sempre ativo (todos os dias) — comportamento
     * padrão pra lanchonetes que não usam essa restrição.
     * Ex: feijoada = "QUA,SAB" → só aparece pra cliente nesses dias.
     * Filtro é aplicado no CardapioService, produto invisível pro cliente
     * fora do horário. Continua editável no painel do restaurante.
     */
    @Column(name = "dias_semana_ativos", length = 40)
    private String diasSemanaAtivos;

    @CreationTimestamp
    private LocalDateTime criadoEm;
}