package com.mydelivery.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;

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
 * Mensagem dentro de um ticket. Autor pode ser:
 *  - RESTAURANTE: dono do restaurante
 *  - SISTEMA:    mensagens automáticas (acknowledge, mudança de status)
 *  - ATENDENTE:  staff do MyDelivery (uso do futuro painel admin)
 */
@Entity
@Table(name = "suporte_mensagens", indexes = {
        @Index(name = "idx_msg_ticket", columnList = "ticket_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuporteMensagem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private SuporteTicket ticket;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Autor autor;

    /** Nome de display do autor (Restaurante, "MyDelivery Bot", "Equipe MyDelivery"). */
    @Column(name = "autor_nome", length = 80)
    private String autorNome;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String texto;

    @OneToMany(mappedBy = "mensagem", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SuporteAnexo> anexos = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false)
    private LocalDateTime criadoEm;

    public enum Autor {
        RESTAURANTE, SISTEMA, ATENDENTE
    }
}
