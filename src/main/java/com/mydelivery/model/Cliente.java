package com.mydelivery.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "clientes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cliente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(nullable = false)
    private String nome;

    private String telefone;
    private String email;
    /** Endereço completo concatenado — legado, mantido pra retrocompat com
     *  registros antigos. Novos pedidos populam também os 5 campos abaixo. */
    private String endereco;

    /** Último endereço estruturado usado pelo cliente — atualizado a cada
     *  pedido DELIVERY. Permite pré-preencher checkout em pedido seguinte
     *  e reduzir atrito ("já sei seu endereço, escolha só o pagamento"). */
    @Column(name = "endereco_rua", length = 200)
    private String enderecoRua;

    @Column(name = "endereco_numero", length = 20)
    private String enderecoNumero;

    @Column(name = "endereco_complemento", length = 120)
    private String enderecoComplemento;

    @Column(name = "endereco_bairro", length = 120)
    private String enderecoBairro;

    @Column(name = "endereco_referencia", length = 200)
    private String enderecoReferencia;

    @Column(name = "endereco_cidade", length = 120)
    private String enderecoCidade;

    @Column(name = "endereco_estado", length = 10)
    private String enderecoEstado;

    @Column(name = "endereco_cep", length = 10)
    private String enderecoCep;

    /**
     * UUID gerado no dispositivo do cliente e persistido no localStorage
     * do navegador. Escopado por restaurante: unique composto (restaurante_id,
     * device_uuid) — mesmo aparelho pedindo em 2 lojas gera 2 UUIDs distintos.
     * Isso mantém o isolamento por design (não vaza cliente entre lojas).
     */
    @Column(name = "device_uuid", length = 36)
    private String deviceUuid;

    /** FK opcional pro último pedido — pra o modal "Pedir novamente" carregar
     *  em O(1). Se o pedido for apagado, o FK vira NULL (ON DELETE SET NULL).
     *
     *  <p>Excluído de {@code equals}/{@code hashCode}/{@code toString} porque
     *  Pedido também tem {@code Cliente cliente} — ciclo bidirecional que
     *  provoca {@link StackOverflowError} no {@code @Data} do Lombok quando
     *  algo compara/serializa. Bug caiu em prod dia 10/jul/2026 causando 500
     *  no criar-pedido (Railway logs mostravam recursão infinita em
     *  Cliente.hashCode ↔ Pedido.hashCode). */
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @JoinColumn(name = "ultimo_pedido_id")
    private Pedido ultimoPedido;

    @Column(name = "ultimo_pedido_em")
    private LocalDateTime ultimoPedidoEm;

    /** Contador desnormalizado — evita COUNT(*) toda vez que o admin abre a ficha. */
    @Column(name = "total_pedidos", nullable = false)
    private Integer totalPedidos = 0;

    @CreationTimestamp
    private LocalDateTime criadoEm;
}