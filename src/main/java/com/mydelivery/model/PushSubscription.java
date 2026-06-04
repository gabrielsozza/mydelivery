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
 * Inscrição Web Push de um aparelho específico de um restaurante.
 *
 * Quando o usuário aceita a permissão de notificações no navegador, ele
 * recebe um objeto PushSubscription com endpoint único (URL do servidor
 * de push do browser) + chaves p256dh/auth. Salvamos esse trio aqui.
 *
 * Quando há novo pedido / chamada de garçom, o backend usa esses dados
 * pra mandar push via FCM (gratuito). Aparelho recebe e o Service Worker
 * mostra a notificação MESMO COM A TELA BLOQUEADA.
 *
 * Único por (restaurante, endpoint): cada aparelho/navegador tem seu
 * endpoint, e o mesmo restaurante pode ter N aparelhos cadastrados
 * (celular do dono + tablet do caixa + desktop do escritório).
 */
@Entity
@Table(
    name = "push_subscriptions",
    indexes = { @Index(name = "idx_push_rest", columnList = "restaurante_id") },
    uniqueConstraints = { @UniqueConstraint(columnNames = {"restaurante_id", "endpoint"}) }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "restaurante_id", nullable = false)
    private Long restauranteId;

    /** URL do servidor de push (varia por browser/sistema). */
    @Column(nullable = false, length = 600)
    private String endpoint;

    /** Chave pública ECDH do cliente (base64url). */
    @Column(name = "p256dh", nullable = false, length = 200)
    private String p256dh;

    /** Auth secret do cliente (base64url). */
    @Column(name = "auth_key", nullable = false, length = 50)
    private String auth;

    /** Identificador legível pro user gerenciar ("Celular do dono", etc). */
    @Column(length = 100)
    private String rotulo;

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false)
    private LocalDateTime criadoEm;
}
