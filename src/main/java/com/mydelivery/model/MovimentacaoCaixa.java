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
 * Movimentação individual dentro de um caixa aberto. Tipos:
 * <ul>
 *   <li><b>VENDA_*</b> — pedido do balcão vinculado ao caixa. {@code pedidoId}
 *       preenchido. Um pedido com pagamento dividido gera N registros (um
 *       por forma) pra os relatórios financeiros ficarem corretos.</li>
 *   <li><b>SANGRIA</b> — retirada em dinheiro pra cofre/despesa. Valor
 *       positivo, mas subtrai do esperado.</li>
 *   <li><b>SUPRIMENTO</b> — entrada em dinheiro (troco extra, aporte).
 *       Soma no esperado.</li>
 *   <li><b>AJUSTE</b> — reservado pra correções manuais (fase futura).</li>
 * </ul>
 *
 * <p>Nunca deletar movimentações — {@link CaixaService#registrarVenda} é
 * idempotente por {@code pedidoId+tipo} pra proteger contra retry.
 */
@Entity
@Table(name = "movimentacoes_caixa", indexes = {
        @Index(name = "idx_mv_caixa", columnList = "caixa_id"),
        @Index(name = "idx_mv_caixa_tipo", columnList = "caixa_id,tipo"),
        @Index(name = "idx_mv_pedido", columnList = "pedido_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovimentacaoCaixa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "caixa_id", nullable = false)
    private Caixa caixa;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Tipo tipo;

    /** Sempre positivo. O tipo determina se soma ou subtrai do esperado. */
    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal valor;

    /** Descrição livre — obrigatória em SANGRIA/SUPRIMENTO. */
    @Column(columnDefinition = "TEXT")
    private String descricao;

    /**
     * Pedido origem, quando for VENDA_*. Não é FK forte (SET NULL on delete)
     * pra permitir deletar pedidos antigos sem quebrar histórico do caixa.
     */
    @Column(name = "pedido_id")
    private Long pedidoId;

    /** Email de quem registrou (herda do operador do caixa se null). */
    @Column(name = "operador_email", length = 120)
    private String operadorEmail;

    @CreationTimestamp
    @Column(name = "criado_em", updatable = false, nullable = false)
    private LocalDateTime criadoEm;

    public enum Tipo {
        VENDA_DINHEIRO,
        VENDA_PIX,
        VENDA_CREDITO,
        VENDA_DEBITO,
        SANGRIA,
        SUPRIMENTO,
        AJUSTE
    }
}
