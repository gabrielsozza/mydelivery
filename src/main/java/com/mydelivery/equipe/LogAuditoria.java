package com.mydelivery.equipe;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Registro de ação sensível no sistema, gravado pelo AuditoriaService.
 *
 * Multi-tenant por restauranteId (indexado). Consulta típica:
 *   "logs desse restaurante, últimos 30 dias, ordem desc" → coberto pelo índice.
 *
 * ator_label é DERIVADO no momento da gravação (ex: "Ana (Gerente)" ou
 * "Gabriel (Proprietário)") — não JOIN em runtime pra listar. Isso vale
 * quando o nome do membro muda ou o membro é excluído: o log preserva
 * quem era na hora.
 *
 * detalhes_json é o "extra" (id da entidade afetada, valores antes/depois,
 * etc). Pra minimizar acoplamento, guarda como texto — leitor decide se
 * parseia como JSON.
 */
@Entity
@Table(name = "logs_auditoria", indexes = {
    @Index(name = "idx_log_rest_ts", columnList = "restaurante_id,criado_em"),
    @Index(name = "idx_log_membro", columnList = "membro_id"),
    @Index(name = "idx_log_acao", columnList = "acao")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogAuditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "restaurante_id", nullable = false)
    private Long restauranteId;

    /** Null quando o ator é o proprietário (Usuario 1:1 com Restaurante). */
    @Column(name = "membro_id")
    private Long membroId;

    /** Ex: "Gabriel Sozza (Proprietário)" ou "Ana Silva (Gerente)". Snapshot. */
    @Column(name = "ator_label", length = 200)
    private String atorLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AcaoAuditoria acao;

    /** Ex: "Pedido", "Produto", "MembroEquipe". Facilita filtrar por tipo. */
    @Column(name = "entidade_tipo", length = 40)
    private String entidadeTipo;

    /** Id da entidade afetada em string (aceita long, uuid, slug). */
    @Column(name = "entidade_id", length = 60)
    private String entidadeId;

    /** JSON livre com contexto. Nunca senhas/tokens. */
    @Column(name = "detalhes_json", columnDefinition = "TEXT")
    private String detalhesJson;

    @Column(length = 60)
    private String ip;

    @CreationTimestamp
    @Column(name = "criado_em", nullable = false, updatable = false)
    private LocalDateTime criadoEm;
}
