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
 * Cada tentativa de auto-correção executada pelo job (ou pelo admin
 * via botão) vira uma linha aqui. Permite auditar: "tentei X às 10:32,
 * falhou porque Z; tentei Y às 10:47, deu OK".
 */
@Entity
@Table(name = "whatsapp_acoes_automaticas", indexes = {
        @Index(name = "idx_acao_em", columnList = "em DESC"),
        @Index(name = "idx_acao_incidente", columnList = "incidente_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsappAcaoAutomatica {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Pode ser null se a ação foi disparada manualmente (não ligada a um incidente). */
    @ManyToOne
    @JoinColumn(name = "incidente_id")
    private WhatsappIncidente incidente;

    @ManyToOne(optional = false)
    @JoinColumn(name = "instance_id", nullable = false)
    private WhatsappInstance instance;

    @Column(nullable = false)
    private LocalDateTime em;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Acao acao;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Resultado resultado;

    /** Detalhe livre (mensagem de erro, código de retorno, etc). */
    @Column(length = 1000)
    private String detalhe;

    public enum Acao {
        RECONECTAR,       // restart do Baileys
        RESET_FULL,       // logout + delete + recriar
        RECRIAR_INSTANCIA,// só create (caso instância sumiu)
        NOOP,             // detector decidiu não agir (cooldown, etc)
        NOTIFICAR_ADMIN   // escalou pra humano
    }

    public enum Resultado {
        OK, FALHA, TIMEOUT
    }
}
