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
 * Registro de envio individual dentro de uma campanha.
 *
 * <p>Serve dois propósitos:
 * <ol>
 *   <li>Auditoria — histórico de quem recebeu / não recebeu / falhou.</li>
 *   <li>Deduplicação — unique (campanha_id, telefone) garante que o mesmo
 *       cliente não recebe duas vezes a MESMA campanha, mesmo em caso de
 *       retry ou crash do sender.</li>
 * </ol>
 *
 * Row status:
 *  - {@code enviadoEm = null AND sucesso = null} → pendente na fila
 *  - {@code enviadoEm != null AND sucesso = true} → entregue à Uazapi
 *  - {@code enviadoEm != null AND sucesso = false} → falhou (erroDetalhe preenchido)
 */
@Entity
@Table(name = "promo_campanha_envios",
        uniqueConstraints = @UniqueConstraint(name = "uq_promo_env_camp_tel",
                columnNames = {"campanha_id", "telefone"}),
        indexes = {
                @Index(name = "idx_promo_env_camp_pendente", columnList = "campanha_id, enviado_em"),
                @Index(name = "idx_promo_env_tel", columnList = "telefone")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromoCampanhaEnvio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "campanha_id", nullable = false)
    private PromoCampanha campanha;

    /** Formato: 55DDDNNNNNNNN (mesmo que Cliente.telefone). */
    @Column(nullable = false, length = 20)
    private String telefone;

    /** Nome do cliente pra personalizar saudação. Fallback: null → "Olá". */
    @Column(name = "nome_cliente", length = 120)
    private String nomeCliente;

    /** Null enquanto pendente; setado quando o sender processa. */
    @Column(name = "enviado_em")
    private LocalDateTime enviadoEm;

    /** Null enquanto pendente; true/false após tentativa de envio. */
    private Boolean sucesso;

    /** Se {@code sucesso=false}, mensagem curta do erro pra debug do dono. */
    @Column(name = "erro_detalhe", length = 300)
    private String erroDetalhe;
}
