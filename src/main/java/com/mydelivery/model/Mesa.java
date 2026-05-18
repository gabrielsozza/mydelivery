package com.mydelivery.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

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
 * Mesa física do restaurante associada a um QR Code.
 *
 * O dono cria quantas quiser (não tem mais limite fixo de 5). Cada mesa tem:
 *  - nome livre ("Mesa 01", "Varanda", "Área Externa") pra exibição
 *  - slug curto ("mesa-01", "varanda") usado no link do QR
 *
 * O QR Code aponta pra https://app/{slug-restaurante}?mesa={slug-mesa},
 * que é o mesmo cardápio público mas em fluxo presencial — sem endereço,
 * sem taxa de entrega, com identificação por nome do cliente.
 *
 * Slug é único por restaurante (composite unique) — dois restaurantes podem
 * ter "mesa-01" sem conflito.
 */
@Entity
@Table(
    name = "mesas",
    uniqueConstraints = @UniqueConstraint(name = "uk_mesa_slug_rest", columnNames = {"restaurante_id", "slug"}),
    indexes = @Index(name = "ix_mesa_restaurante", columnList = "restaurante_id")
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Mesa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    /** Nome exibido pro dono e nos pedidos. Ex: "Mesa 01", "Área Externa". */
    @Column(nullable = false, length = 60)
    private String nome;

    /** Identificador URL-safe (kebab-case). Compõe o link do QR: ?mesa={slug}. */
    @Column(nullable = false, length = 60)
    private String slug;

    @Column(nullable = false)
    @Builder.Default
    private Boolean ativa = true;

    @CreationTimestamp
    @Column(name = "criada_em", updatable = false)
    private LocalDateTime criadaEm;
}
