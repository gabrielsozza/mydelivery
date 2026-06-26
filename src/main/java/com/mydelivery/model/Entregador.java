package com.mydelivery.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "entregadores")
public class Entregador {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    @Column(nullable = false, length = 100)
    private String nome;

    @Column(length = 20)
    private String telefone;

    @Column(length = 50)
    private String veiculo;

    @Column(length = 20)
    private String placa;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DISPONIVEL;

    @Column(nullable = false)
    private Boolean ativo = true;

    /**
     * PIN de 4-6 dígitos pra login do entregador no app mobile.
     * Armazenado em texto plano pra suportar "Mostrar PIN" no painel do dono.
     * Risco aceitável: é credencial operacional curta, multi-tenant (precisa
     * slug do restaurante + pin), não dá acesso a dados sensíveis fora do
     * pedido próprio. Plaintext > UX ruim de regerar a cada vez.
     */
    @Column(name = "pin", length = 8)
    private String pin;

    /**
     * Marker de presença: true quando entregador fez login recentemente
     * no app e não saiu manualmente. Auto-resetado pra false em logout ou
     * cleanup periódico (futuro). Usado no painel admin pra mostrar quem
     * está online.
     */
    @Column(nullable = false)
    private Boolean online = false;

    /** Última vez que o entregador autenticou via PIN. Null se nunca logou. */
    @Column(name = "ultimo_login_em")
    private LocalDateTime ultimoLoginEm;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime criadoEm;

    public enum Status { DISPONIVEL, EM_ENTREGA, INATIVO }
}
