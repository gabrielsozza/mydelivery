package com.mydelivery.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Estado persistente e idempotente de uma proposta de repetição. */
@Entity
@Table(name = "whatsapp_repeticao_conversas",
        uniqueConstraints = @UniqueConstraint(name = "ux_whatsapp_repeticao_origem",
                columnNames = {"restaurante_id", "telefone_hash", "pedido_referencia_id"}),
        indexes = {
            @Index(name = "ix_whatsapp_repeticao_relatorio", columnList = "restaurante_id, ofertado_em, estado"),
            @Index(name = "ix_whatsapp_repeticao_ativa", columnList = "restaurante_id, telefone_hash, expira_em")
        })
@Getter
@Setter
@NoArgsConstructor
public class WhatsappRepeticaoConversa {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(name = "telefone_hash", nullable = false, length = 64)
    private String telefoneHash;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "pedido_referencia_id", nullable = false)
    private Pedido pedidoReferencia;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private Estado estado;

    @Column(name = "pagamento_escolhido", length = 32)
    private String pagamentoEscolhido;

    @Column(name = "endereco_confirmado", nullable = false)
    private boolean enderecoConfirmado;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "pedido_criado_id")
    private Pedido pedidoCriado;

    @Column(name = "link_token_hash", length = 64, unique = true)
    private String linkTokenHash;

    @Column(name = "link_token_expira_em")
    private LocalDateTime linkTokenExpiraEm;

    @CreationTimestamp @Column(name = "ofertado_em", nullable = false, updatable = false)
    private LocalDateTime ofertadoEm;

    @Column(name = "aceito_em")
    private LocalDateTime aceitoEm;

    @Column(name = "expira_em", nullable = false)
    private LocalDateTime expiraEm;

    @UpdateTimestamp @Column(name = "atualizado_em", nullable = false)
    private LocalDateTime atualizadoEm;

    public enum Estado {
        OFERTA_DE_REPETICAO, VALIDANDO_ENDERECO, AGUARDANDO_CONFIRMACAO_FINAL,
        PEDIDO_CRIADO, RECUSADO, EXPIRADO, ENCAMINHADO_CARDAPIO
    }
}
