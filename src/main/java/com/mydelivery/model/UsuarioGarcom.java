package com.mydelivery.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

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
 * Garçom — login simples por PIN no tablet/celular.
 *
 * Não usa email/senha porque o tablet do salão é compartilhado entre
 * turnos. PIN curto + UNIQUE por restaurante é suficiente — escopo é
 * limitado aos endpoints /api/restaurante/garcom/**.
 *
 * pin_hash usa BCrypt como o resto do sistema. PIN limpo nunca é guardado.
 */
@Entity
@Table(name = "usuarios_garcom",
       indexes = { @Index(name = "idx_garcom_rest", columnList = "restaurante_id") },
       uniqueConstraints = {
           // Não permite 2 garçons com mesmo PIN no mesmo restaurante
           // (senão login fica ambíguo). Mesmo PIN entre restaurantes ok.
           @UniqueConstraint(name = "uk_garcom_rest_pin", columnNames = { "restaurante_id", "pin_hash" })
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsuarioGarcom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "restaurante_id", nullable = false)
    private Long restauranteId;

    @Column(nullable = false, length = 80)
    private String nome;

    /** Hash BCrypt do PIN (4-6 dígitos). */
    @Column(name = "pin_hash", nullable = false, length = 100)
    private String pinHash;

    @Builder.Default
    @Column(nullable = false)
    private Boolean ativo = true;

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false)
    private LocalDateTime criadoEm;
}
