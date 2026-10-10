package com.mydelivery.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Campanha de disparo de promoção pelo WhatsApp.
 *
 * Fluxo:
 *  RASCUNHO → ENVIANDO → CONCLUIDA (fim natural)
 *  RASCUNHO → CANCELADA (usuário desistiu antes de disparar)
 *  ENVIANDO ↔ PAUSADA (dono pode pausar / retomar a qualquer momento)
 *  ENVIANDO/PAUSADA → CANCELADA (dono cancela no meio)
 *
 * Histórico anterior ao CRM responsável. Novas campanhas usam campanhas_crm.
 */
@Entity
@Table(name = "promo_campanhas", indexes = {
        @Index(name = "idx_promo_camp_rest_status", columnList = "restaurante_id, status"),
        @Index(name = "idx_promo_camp_criada", columnList = "criada_em")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromoCampanha {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    /** Texto que o cliente vai receber. Máx. 900 chars (mesmo cap do WhatsApp). */
    @Column(nullable = false, length = 900)
    private String texto;

    /** Imagem opcional hospedada pelo serviço de upload existente. */
    @Column(name = "imagem_url", length = 1500)
    private String imagemUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.RASCUNHO;

    /** Quantidade mínima de pedidos passados pra cliente entrar na fila.
     *  Default 1 — envia pra TODOS os clientes cadastrados (a base da aba
     *  Clientes). Dono pode aumentar pra 2+ pra restringir a recorrentes. */
    @Column(name = "min_compras", nullable = false)
    @Builder.Default
    private Integer minCompras = 1;

    @Column(name = "criada_em", nullable = false)
    private LocalDateTime criadaEm;

    @Column(name = "iniciada_em")
    private LocalDateTime iniciadaEm;

    @Column(name = "concluida_em")
    private LocalDateTime concluidaEm;

    /** Total de destinatários que entraram na fila quando iniciou. */
    @Column(name = "total_destinos", nullable = false)
    @Builder.Default
    private Integer totalDestinos = 0;

    @Column(name = "total_enviados", nullable = false)
    @Builder.Default
    private Integer totalEnviados = 0;

    @Column(name = "total_falhas", nullable = false)
    @Builder.Default
    private Integer totalFalhas = 0;

    public enum Status {
        RASCUNHO, ENVIANDO, PAUSADA, CONCLUIDA, CANCELADA
    }
}
