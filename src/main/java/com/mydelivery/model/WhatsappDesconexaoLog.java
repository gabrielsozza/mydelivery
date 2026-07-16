package com.mydelivery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Trilha de auditoria de eventos de queda/reconexão por instância.
 *
 * <p>Antes: quando uma instância caía, o único registro era
 * {@code WhatsappInstance.motivoUltimaQueda} (só o último). Isso deixava
 * o admin sem histórico pra diagnosticar "instância X cai toda semana às
 * 22h" ou "instância Y reconectou 5x hoje e sempre falhou".
 *
 * <p>Agora: 1 linha por evento. Um ciclo típico gera:
 * <pre>
 *   QUEDA          — instância caiu, motivo="webhook connection close"
 *   RECONEXAO_TENTADA — health job disparou /instance/restart
 *   RECONEXAO_OK    — conectou de novo (correlationId liga os 3 eventos)
 * </pre>
 *
 * <p>Retention: infinito por enquanto. Cada instância gera ~2-5 eventos
 * por semana em condição normal (queda + tentativa + resultado).
 * Se o volume virar problema, cortar linhas com criadoEm > 90d.
 */
@Entity
@Table(name = "whatsapp_desconexao_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsappDesconexaoLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK sem carregar a entity — evita LazyInit e economiza query. */
    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    /** Snapshot do nome (instance pode ser deletada). */
    @Column(name = "instance_name", nullable = false, length = 80)
    private String instanceName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Tipo tipo;

    public enum Tipo {
        /** Instância mudou de CONECTADA pra DESCONECTADA (webhook ou health). */
        QUEDA,
        /** HealthJob disparou /instance/restart. */
        RECONEXAO_TENTADA,
        /** Reconexão restaurou conexão (webhook connection.open). */
        RECONEXAO_OK,
        /** Tentativa não teve efeito depois de N segundos. */
        RECONEXAO_FALHA,
        /** HeartbeatJob detectou algo ruim (phone sumiu, 5xx recorrente). */
        HEARTBEAT_FALHOU,
        /** Sistema marcou como INSTAVEL sem queda física (shadow ban invisível). */
        MARCADA_INSTAVEL,
        /** Admin agiu manualmente (marcar veterana, forçar reconexão, etc). */
        ACAO_ADMIN
    }

    @Column(length = 200)
    private String motivo;

    /** Código bruto retornado pela Uazapi quando aplicável (ex: "HTTP 500"). */
    @Column(name = "codigo_api", length = 60)
    private String codigoApi;

    @Column(name = "status_antes", length = 25)
    private String statusAntes;

    @Column(name = "status_depois", length = 25)
    private String statusDepois;

    /** Início do ciclo de conexão que terminou (só em QUEDA). */
    @Column(name = "conectado_desde")
    private LocalDateTime conectadoDesde;

    /** Duração do ciclo em minutos (só em QUEDA). */
    @Column(name = "duracao_min")
    private Integer duracaoMin;

    @Column(name = "msgs_processadas_no_ciclo")
    private Integer msgsProcessadasNoCiclo;

    /** Número da tentativa (1, 2, 3...) — só em RECONEXAO_TENTADA. */
    @Column(name = "tentativa_num")
    private Integer tentativaNum;

    /**
     * Correlation ID pra amarrar eventos relacionados: mesmo ID em QUEDA →
     * RECONEXAO_TENTADA → RECONEXAO_OK/FALHA. Formato:
     * {@code "wa-{instanceName}-{ts}"}.
     */
    @Column(name = "correlation_id", length = 60)
    private String correlationId;

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false, nullable = false)
    private LocalDateTime criadoEm;
}
