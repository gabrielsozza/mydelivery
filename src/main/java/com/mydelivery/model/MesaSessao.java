package com.mydelivery.model;

import java.math.BigDecimal;
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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sessão de atendimento de uma mesa — do momento que o cliente senta até a
 * conta ser fechada. Distinta do {@link Pedido}: uma sessão pode ter VÁRIOS
 * pedidos (cliente pediu rodada de bebida → mais um prato → sobremesa).
 *
 * Centraliza:
 *  - Tempo de atendimento (abertura → fechamento)
 *  - Acompanhamento do score de prioridade (mesa esquecida)
 *  - Vínculo opcional com garçom responsável
 *  - Total acumulado (cache pra evitar SUM custoso)
 *
 * Apenas UMA sessão ABERTA por mesa simultaneamente. Garantido por lógica
 * no service — não por constraint DB (a constraint não consegue expressar
 * "única onde resolvido_em IS NULL" em todos os bancos).
 */
@Entity
@Table(name = "mesa_sessoes", indexes = {
        @Index(name = "idx_sessao_mesa_aberta", columnList = "mesa_id, fechamento_em"),
        @Index(name = "idx_sessao_restaurante_aberta", columnList = "restaurante_id, fechamento_em")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MesaSessao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "mesa_id", nullable = false)
    private Mesa mesa;

    /** Denormalizado pra query rápida por restaurante. */
    @Column(name = "restaurante_id", nullable = false)
    private Long restauranteId;

    /** Garçom que ABRIU a sessão. Pode ser null se aberta por outro fluxo
     *  (ex: cliente escaneou QR e abriu pelo app). */
    @Column(name = "garcom_id")
    private Long garcomId;

    @CreationTimestamp
    @Column(name = "abertura_em", updatable = false, nullable = false)
    private LocalDateTime aberturaEm;

    /** null = sessão ainda aberta. Quando preenche, vira sessão fechada. */
    @Column(name = "fechamento_em")
    private LocalDateTime fechamentoEm;

    @Column(name = "nome_cliente", length = 100)
    private String nomeCliente;

    /** Telefone usado pra reconhecer cliente recorrente (memória entre visitas). */
    @Column(name = "telefone_cliente", length = 20)
    private String telefoneCliente;

    @Builder.Default
    @Column(nullable = false)
    private Integer pessoas = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.ABERTA;

    /**
     * Última vez que houve QUALQUER interação na mesa:
     *  - garçom abriu a comanda
     *  - novo pedido lançado
     *  - cliente chamou (ChamadaGarcom)
     *  - status mudou
     *
     * Usado pra calcular "mesa esquecida" (score de prioridade).
     */
    @Column(name = "ultima_interacao_em", nullable = false)
    private LocalDateTime ultimaInteracaoEm;

    /** Cache do total acumulado. Atualizado quando pedido é criado/cancelado.
     *  Não é a fonte da verdade — o SUM dos pedidos é. Mas evita query custosa
     *  no mapa do salão (que mostra valor de cada mesa). */
    @Builder.Default
    @Column(name = "total_acumulado", precision = 10, scale = 2, nullable = false)
    private BigDecimal totalAcumulado = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String observacoes;

    public enum Status {
        /** Acabou de abrir, sem pedidos ainda. */
        ABERTA,
        /** Cliente chamou garçom ou está há tempo sem interação. */
        AGUARDANDO_ATENDIMENTO,
        /** Tem ao menos 1 pedido lançado pra cozinha. */
        PEDIDO_ENVIADO,
        /** Cliente pediu a conta. */
        CONTA_SOLICITADA,
        /** Cliente confirmou pagamento mas ainda não saiu. */
        PAGAMENTO_PENDENTE,
        /** Pago, mesa pode receber novo cliente. */
        FECHADA
    }
}
