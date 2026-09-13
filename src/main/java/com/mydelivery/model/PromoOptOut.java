package com.mydelivery.model;

import java.time.LocalDateTime;

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
 * Registro de opt-out: cliente respondeu "PARE" / "SAIR" / "nao quero receber" —
 * nunca mais entra em campanha desse restaurante.
 *
 * <p>Chave: (restaurante, telefone) UNIQUE. Um cliente pode ter opt-out em uma
 * loja e continuar recebendo de outra — decisao é por restaurante.
 */
@Entity
@Table(name = "promo_opt_outs",
        uniqueConstraints = @UniqueConstraint(name = "uq_promo_optout_rest_tel",
                columnNames = {"restaurante_id", "telefone"}),
        indexes = @Index(name = "idx_promo_optout_rest", columnList = "restaurante_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromoOptOut {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(nullable = false, length = 20)
    private String telefone;

    /** Palavra que disparou o opt-out (ex: "PARE", "SAIR") — auditoria. */
    @Column(length = 60)
    private String motivo;

    @Column(name = "criado_em", nullable = false)
    private LocalDateTime criadoEm;
}
