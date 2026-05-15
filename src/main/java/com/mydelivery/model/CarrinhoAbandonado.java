package com.mydelivery.model;

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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "carrinhos_abandonados")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarrinhoAbandonado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @ManyToOne
    @JoinColumn(name = "cliente_id")
    private Cliente cliente;

    // telefone e nome para caso o cliente não esteja cadastrado
    private String telefoneCliente;
    private String nomeCliente;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String itensJson; // JSON dos itens do carrinho

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String sessionId; // identifica o carrinho sem login

    private LocalDateTime notificadoEm; // quando o e-mail foi disparado

    @CreationTimestamp
    private LocalDateTime criadoEm;

    @UpdateTimestamp
    private LocalDateTime atualizadoEm;

    public enum Status {
        ATIVO,       // carrinho salvo, pedido não confirmado
        NOTIFICADO,  // e-mail disparado, aguardando retorno
        RECUPERADO,  // cliente voltou e finalizou o pedido
        IGNORADO     // passou tempo demais, arquivado
    }
}