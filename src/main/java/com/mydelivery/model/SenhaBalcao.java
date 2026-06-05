package com.mydelivery.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Senha sequencial DIÁRIA do balcão.
 *
 * Por que diário? O cliente espera ver "Senha 47", não "Senha 18472".
 * Cada dia reseta a contagem (1, 2, 3...). UNIQUE garante que dois pedidos
 * do mesmo restaurante no mesmo dia tenham senhas diferentes.
 *
 * Vínculo com pedido é 1:1. Quando pedido é cancelado, senha pode ficar
 * órfã — não tem problema, a contagem do dia seguinte reseta.
 */
@Entity
@Table(name = "senhas_balcao",
       indexes = {
           @Index(name = "idx_senha_rest_data", columnList = "restaurante_id, data_emissao"),
           @Index(name = "idx_senha_pedido", columnList = "pedido_id")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_senha_rest_data_num",
                             columnNames = {"restaurante_id", "data_emissao", "numero"})
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SenhaBalcao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "restaurante_id", nullable = false)
    private Long restauranteId;

    /** Número sequencial diário (1, 2, 3...). Reseta todo dia. */
    @Column(nullable = false)
    private Integer numero;

    @Column(name = "data_emissao", nullable = false)
    private LocalDate dataEmissao;

    @Column(name = "pedido_id", nullable = false)
    private Long pedidoId;

    /** Nome do cliente associado à senha — opcional, pra chamar "JOÃO" em vez de "SENHA 47". */
    @Column(name = "nome_cliente", length = 50)
    private String nomeCliente;

    @CreationTimestamp
    @Column(name = "criada_em", updatable = false)
    private LocalDateTime criadaEm;
}
