package com.mydelivery.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
 * Cupom de desconto. Pode ser:
 * - MANUAL: criado pelo dono do restaurante, com regras flexíveis
 * - FIDELIDADE: gerado automaticamente quando um cliente atinge o threshold do programa.
 *               Nesse caso vem vinculado ao telefone do cliente e tem uso único.
 */
@Entity
@Table(name = "cupons",
    uniqueConstraints = @UniqueConstraint(name = "uk_cupom_codigo_restaurante", columnNames = {"codigo", "restaurante_id"}),
    indexes = {
        @Index(name = "idx_cupom_telefone", columnList = "telefone_cliente"),
        @Index(name = "idx_cupom_codigo", columnList = "codigo")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cupom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(nullable = false, length = 40)
    private String codigo;          // ex: BEMVINDO10, FID-AB12CD

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tipo tipo;              // PERCENT, FIXO, ITEM_GRATIS

    // Valor do desconto. Para ITEM_GRATIS pode ser null (descrição cobre)
    @Column(precision = 10, scale = 2)
    private BigDecimal valor;

    // Para ITEM_GRATIS — descrição mostrada ao cliente
    @Column(length = 200)
    private String descricao;

    // ── Regras de aplicação ──
    @Column(name = "pedido_minimo", precision = 10, scale = 2)
    private BigDecimal pedidoMinimo;

    @Column(name = "validade_inicio")
    private LocalDateTime validadeInicio;

    @Column(name = "validade_fim")
    private LocalDateTime validadeFim;

    // Limite total de usos (null = ilimitado)
    @Column(name = "limite_total")
    private Integer limiteTotal;

    // Limite por cliente (null = ilimitado)
    @Column(name = "limite_por_cliente")
    private Integer limitePorCliente;

    // Modos onde o cupom é válido (delivery, retirada, mesa). Lista vazia = todos.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cupom_modos", joinColumns = @JoinColumn(name = "cupom_id"))
    @Column(name = "modo")
    @Builder.Default
    private List<String> modosAplicaveis = new ArrayList<>();

    @Builder.Default
    @Column(nullable = false)
    private Boolean ativo = true;

    // ── Origem ──
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private Origem origem = Origem.MANUAL;

    // Para cupons de FIDELIDADE: telefone do cliente que pode usar
    // Para MANUAL: null (qualquer cliente pode usar respeitando os limites)
    @Column(name = "telefone_cliente", length = 20)
    private String telefoneCliente;

    // Para cupons one-shot da fidelidade: quando foi usado (null = ainda válido)
    @Column(name = "usado_em")
    private LocalDateTime usadoEm;

    @CreationTimestamp
    @Column(name = "criado_em", nullable = false, updatable = false)
    private LocalDateTime criadoEm;

    @UpdateTimestamp
    @Column(name = "atualizado_em")
    private LocalDateTime atualizadoEm;

    public enum Tipo {
        PERCENT, FIXO, ITEM_GRATIS
    }

    public enum Origem {
        MANUAL, FIDELIDADE
    }
}
