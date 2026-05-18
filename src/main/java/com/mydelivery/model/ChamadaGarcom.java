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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Chamada de garçom feita pelo cliente da mesa.
 *
 * Fluxo:
 *  - Cliente toca no botão "Chamar Garçom" no cardápio mobile.
 *  - Backend cria registro PENDENTE com (restaurante, mesa).
 *  - Painel do dono faz polling em /api/chamadas-garcom/pendentes (10s);
 *    quando aparece nova chamada, toca som e mostra notificação.
 *  - Atendente clica "Atendi" → DELETE marca como ATENDIDA.
 */
@Entity
@Table(
    name = "chamadas_garcom",
    indexes = {
        @Index(name = "ix_chamada_restaurante_status", columnList = "restaurante_id, status"),
        @Index(name = "ix_chamada_criada_em", columnList = "criada_em")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChamadaGarcom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @ManyToOne
    @JoinColumn(name = "mesa_id", nullable = false)
    private Mesa mesa;

    /** Nome digitado pelo cliente na comanda (opcional — chamada pode ser anônima). */
    @Column(name = "nome_cliente", length = 80)
    private String nomeCliente;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDENTE"; // PENDENTE | ATENDIDA

    @CreationTimestamp
    @Column(name = "criada_em", updatable = false)
    private LocalDateTime criadaEm;

    @Column(name = "atendida_em")
    private LocalDateTime atendidaEm;
}
