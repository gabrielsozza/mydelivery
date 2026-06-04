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
 * Registro persistente de cada problema detectado num bot WhatsApp.
 *
 * Diferente do snapshot contínuo do {@link WhatsappHealthLog} (que conta
 * o "estado contínuo" pra alimentar o gráfico), este registra EVENTOS
 * discretos com causa raiz classificada.
 *
 * Idempotência: nunca existe mais de 1 incidente aberto (resolvido_em
 * null) do mesmo {tipo, instance}. O service garante isso ao abrir.
 */
@Entity
@Table(name = "whatsapp_incidentes", indexes = {
        @Index(name = "idx_inc_resolvido", columnList = "resolvido_em"),
        @Index(name = "idx_inc_instance_tipo_resolvido",
                columnList = "instance_id, tipo, resolvido_em")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsappIncidente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "instance_id", nullable = false)
    private WhatsappInstance instance;

    /** Denormalizado pra facilitar consultas/filtros. */
    @Column(name = "restaurante_id", nullable = false)
    private Long restauranteId;

    @Column(name = "aberto_em", nullable = false)
    private LocalDateTime abertoEm;

    /** null = ainda aberto. */
    @Column(name = "resolvido_em")
    private LocalDateTime resolvidoEm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Tipo tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severidade severidade;

    @Column(name = "causa_provavel", length = 500)
    private String causaProvavel;

    /** JSON livre com detalhes técnicos pro debug do admin. */
    @Column(name = "detalhes_json", columnDefinition = "TEXT")
    private String detalhesJson;

    /** Operador clicou "Marcar visto". Não fecha o incidente, só silencia
     *  o alerta. Quando voltar a OK, fecha sozinho. */
    @Column(name = "ack_em")
    private LocalDateTime ackEm;

    @Column(name = "ack_por", length = 200)
    private String ackPor;

    public enum Tipo {
        /** Status DB=CONECTADA mas mensagem real de cliente > 2h. */
        SHADOW_BAN_SUSPEITO,
        /** consultarStatus na Evolution falhou N vezes seguidas. */
        EVOLUTION_FORA,
        /** /connect retornou 404 — instância sumiu lá. */
        INSTANCIA_DELETADA,
        /** state="connecting" há mais de 5min sem evoluir. */
        BAILEYS_TRAVADO,
        /** enviarTexto retornou 5xx / timeout. */
        ERRO_API_EVOLUTION,
        /** processar() lançou exception não esperada. */
        ERRO_INTERNO_BOT,
        /** webhook na Evolution aponta pra URL errada. */
        WEBHOOK_DESCONFIGURADO,
        /** Auto-reconnect tentou 5x e desistiu. */
        RECUPERACAO_ESGOTADA,
        /** Logout chamado mas Evolution continua mostrando open. */
        SESSAO_ZUMBI
    }

    public enum Severidade {
        BAIXA, MEDIA, ALTA
    }
}
