package com.mydelivery.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Ticket de suporte aberto pelo restaurante.
 * Status simples (3 fases visíveis ao usuário + FECHADO técnico).
 *
 * Estrutura preparada pra painel admin futuro:
 *  - atendenteId (Usuario.id) — quem está atendendo no momento
 *  - prioridade — pra triagem futura
 *  - categoria — sugestão automática pelo título (futuro)
 */
@Entity
@Table(name = "suporte_tickets", indexes = {
        @Index(name = "idx_ticket_restaurante", columnList = "restaurante_id"),
        @Index(name = "idx_ticket_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuporteTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(nullable = false, length = 120)
    private String assunto;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    @Builder.Default
    private Status status = Status.AGUARDANDO;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private Prioridade prioridade = Prioridade.NORMAL;

    @Column(length = 40)
    private String categoria; // ex: "pagamento", "whatsapp", "cardapio" — sugestão automática

    /** ID do usuário atendente que pegou o ticket. Null = ainda na fila. */
    @Column(name = "atendente_id")
    private Long atendenteId;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("criadoEm ASC")
    @Builder.Default
    private List<SuporteMensagem> mensagens = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false)
    private LocalDateTime criadoEm;

    @UpdateTimestamp
    @Column(name = "atualizado_em")
    private LocalDateTime atualizadoEm;

    @Column(name = "resolvido_em")
    private LocalDateTime resolvidoEm;

    public enum Status {
        AGUARDANDO,      // novo, na fila
        EM_ATENDIMENTO,  // atendente pegou
        RESOLVIDO,       // marcado como resolvido (admin)
        FECHADO          // arquivado (não aparece nas listas ativas)
    }

    public enum Prioridade {
        BAIXA, NORMAL, ALTA, URGENTE
    }
}
