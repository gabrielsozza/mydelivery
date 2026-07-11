package com.mydelivery.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
 * Caixa (turno operacional) — abertura + expediente + fechamento.
 *
 * <p>Um caixa representa UM turno de atendimento com valor inicial em
 * dinheiro. Enquanto ABERTO, todas as vendas do balcão + sangrias +
 * suprimentos ficam vinculados a ele via {@link MovimentacaoCaixa}. No
 * fechamento, o operador declara o valor encontrado; o sistema calcula
 * diferença = encontrado − esperado.
 *
 * <p>Estrutura preparada pra evoluir depois:
 * <ul>
 *   <li>{@code operadorEmail} é string livre — quando bater com Equipe,
 *       vira lookup por membro. Já dá pra reportar "fechamento por operador".</li>
 *   <li>Índice único parcial em {@code restaurante_id WHERE status='ABERTO'}
 *       impede 2 caixas abertos simultaneamente (fase 1). Múltiplos caixas
 *       por PDV vai exigir remover o índice e adicionar coluna terminal_id.</li>
 * </ul>
 */
@Entity
@Table(name = "caixas", indexes = {
        @Index(name = "idx_cx_rest_status", columnList = "restaurante_id,status"),
        @Index(name = "idx_cx_aberto_em", columnList = "aberto_em")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Caixa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Multi-tenant: cada caixa é de um restaurante. */
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    /**
     * Email do usuário que abriu o caixa. Guarda mesmo se depois o funcionário
     * sair — mantém rastreabilidade histórica. Não é FK pro Usuario porque
     * membros de equipe têm login separado (não são Usuario).
     */
    @Column(name = "operador_email", length = 120)
    private String operadorEmail;

    /**
     * Nome de exibição do operador — capturado no momento da abertura pra
     * histórico ler sem precisar reconsultar. Se o email trocar, o histórico
     * ainda mostra quem abriu.
     */
    @Column(name = "operador_nome", length = 120)
    private String operadorNome;

    /** Troco/fundo inicial em dinheiro. Nunca null. */
    @Column(name = "valor_inicial", precision = 12, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal valorInicial = BigDecimal.ZERO;

    /**
     * Valor esperado no caixa em dinheiro, calculado no fechamento:
     *   inicial + vendas_dinheiro + suprimentos − sangrias
     * Fica NULL enquanto ABERTO.
     */
    @Column(name = "valor_esperado", precision = 12, scale = 2)
    private BigDecimal valorEsperado;

    /**
     * Valor que o operador CONTOU no caixa físico ao fechar. Junto com
     * {@link #valorEsperado} determina a {@link #diferenca}.
     */
    @Column(name = "valor_encontrado", precision = 12, scale = 2)
    private BigDecimal valorEncontrado;

    /**
     * diferenca = encontrado − esperado
     *  > 0 = SOBRA (mais dinheiro que o esperado)
     *  < 0 = FALTA (menos dinheiro que o esperado)
     *  = 0 = OK
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal diferenca;

    @Column(name = "aberto_em", nullable = false)
    private LocalDateTime abertoEm;

    @Column(name = "fechado_em")
    private LocalDateTime fechadoEm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private Status status = Status.ABERTO;

    /** Observações livres do operador ao fechar (ex.: "sobra por gorjeta"). */
    @Column(name = "observacao_fechamento", columnDefinition = "TEXT")
    private String observacaoFechamento;

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false)
    private LocalDateTime criadoEm;

    @UpdateTimestamp
    @Column(name = "atualizado_em")
    private LocalDateTime atualizadoEm;

    public enum Status {
        ABERTO,
        FECHADO
    }
}
