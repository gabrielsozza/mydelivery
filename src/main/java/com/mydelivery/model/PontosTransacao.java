package com.mydelivery.model;

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
 * Cada transação de pontos é um registro separado — permite expiração FIFO
 * (os pontos mais antigos vencem primeiro) e auditoria completa do saldo.
 *
 * Saldo do cliente = SOMA(pontos) onde tipo = CREDITO AND expiraEm > now
 *                  - SOMA(pontos) onde tipo = DEBITO
 *
 * Para simplificar, armazenamos o saldo via vista filtrada (FidelidadeService.calcularSaldo).
 */
@Entity
@Table(name = "pontos_transacoes", indexes = {
        @Index(name = "idx_pontos_telefone_rest", columnList = "telefone_cliente,restaurante_id"),
        @Index(name = "idx_pontos_expira", columnList = "expira_em")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PontosTransacao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    // Identificamos o cliente pelo telefone (cliente final não loga)
    @Column(name = "telefone_cliente", nullable = false, length = 20)
    private String telefoneCliente;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tipo tipo;

    // Quantidade de pontos: positivo para crédito, positivo também para débito (o sinal vem do `tipo`)
    @Column(nullable = false)
    private Integer pontos;

    // Pra créditos: data em que esses pontos expiram (criadoEm + diasExpiracao do programa)
    // Pra débitos/expiração: nulo
    @Column(name = "expira_em")
    private LocalDateTime expiraEm;

    // Referências opcionais pra auditoria
    @Column(name = "pedido_id")
    private Long pedidoId;       // pedido que gerou o crédito

    @Column(name = "cupom_id")
    private Long cupomId;        // cupom de fidelidade gerado (caso DEBITO_RECOMPENSA)

    @Column(length = 200)
    private String descricao;    // ex: "Compra do pedido #1234"

    @CreationTimestamp
    @Column(name = "criado_em", nullable = false, updatable = false)
    private LocalDateTime criadoEm;

    public enum Tipo {
        CREDITO,              // pontos ganhos por pedido
        DEBITO_RECOMPENSA,    // pontos trocados por cupom de fidelidade
        EXPIRACAO             // pontos vencidos (registro de auditoria)
    }
}
