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
 * Snapshot do estado de saúde do bot do WhatsApp em um ponto no tempo.
 * Gerado a cada 5min pelo WhatsappHealthJob — usado pra gráfico no admin
 * (acompanhamento em tempo real) e pra auditoria.
 *
 *  - OPERACIONAL: status CONECTADA + heartbeat recente (< 30min sem msg ok)
 *  - INSTAVEL: CONECTADA mas sem msg há 30min–3h (suspeita de zumbi)
 *  - OFFLINE: status != CONECTADA OU sem msg há > 3h (zumbi confirmado / desconectada)
 */
@Entity
@Table(name = "whatsapp_health_log", indexes = {
    @Index(name = "ix_wahealth_instance_em", columnList = "instance_id, em DESC")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsappHealthLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "instance_id", nullable = false)
    private WhatsappInstance instance;

    /** Momento da medição. */
    @Column(nullable = false)
    private LocalDateTime em;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Estado estado;

    /** Minutos desde a última mensagem recebida no momento da medição. */
    @Column(name = "min_sem_msg")
    private Integer minutosSemMensagem;

    /** Sinaliza se uma auto-reconexão foi disparada nesse snapshot. */
    @Column(name = "reconexao_disparada", nullable = false)
    @Builder.Default
    private Boolean reconexaoDisparada = false;

    public enum Estado {
        OPERACIONAL,    // tudo normal
        INSTAVEL,       // conectada mas sem eventos há um tempo
        OFFLINE         // não conectada ou zumbi confirmada
    }
}
