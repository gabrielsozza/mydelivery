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

    @CreationTimestamp
    private LocalDateTime criadoEm;
}