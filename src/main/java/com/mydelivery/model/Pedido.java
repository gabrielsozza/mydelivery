package com.mydelivery.model;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;

@Entity @Table(name="pedidos") @Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Pedido {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne @JoinColumn(name="restaurante_id",nullable=false) private Restaurante restaurante;
    @ManyToOne @JoinColumn(name="cliente_id") private Cliente cliente;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="entregador_id") private Entregador entregador;

    // ── Mesa (presencial) ──
    // Quando tipo=MESA, pedido fica vinculado à mesa física e ao nome digitado
    // pelo cliente. Pedidos delivery/retirada deixam ambos null.
    // EAGER porque open-in-view=false: serialização do DTO fora da transação
    // dispararia LazyInitializationException ao ler mesa.getNome().
    @ManyToOne @JoinColumn(name="mesa_id") private Mesa mesa;
    @Column(name="nome_cliente_mesa", length=80) private String nomeClienteMesa;
    // length=30 garante que AGUARDANDO_PAGAMENTO (20 chars) caiba. ddl-auto=update
    // não amplia colunas existentes — pra bancos já criados, ver MigracaoEnumLengthJob.
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=30) private Status status;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) private Tipo tipo;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=30) private FormaPagamento formaPagamento;
    @Column(precision=10,scale=2) private BigDecimal subtotal;
    @Column(precision=10,scale=2) private BigDecimal taxaEntrega=BigDecimal.ZERO;
    @Column(precision=10,scale=2) private BigDecimal desconto=BigDecimal.ZERO;
    @Column(precision=10,scale=2) private BigDecimal total;
    @Column(name="cupom_codigo", length=40) private String cupomCodigo;
    @Column(name="pontos_ganhos") private Integer pontosGanhos;
    @Column(columnDefinition="TEXT") private String observacao;

    // ── Pagamento ──
    // Quando o cliente paga online (PIX/Cartão/ApplePay) vs na entrega (Dinheiro/PIX/etc)
    @Enumerated(EnumType.STRING) @Column(name="modo_pagamento", length=20)
    private ModoPagamento modoPagamento;
    @Column(nullable=false) @Builder.Default
    private Boolean pago = false;
    @Column(name="pago_em")
    private LocalDateTime pagoEm;
    private String enderecoEntrega;
    @Column(name="agendado_para") private LocalDateTime agendadoPara;

    // ── Garçom / Balcão / Divisão ──
    /** Sessão de mesa à qual esse pedido pertence (null pra delivery/balcão). */
    @Column(name="sessao_id") private Long sessaoId;
    /** Garçom que lançou esse pedido (null se foi cliente direto pelo QR). */
    @Column(name="garcom_id") private Long garcomId;
    /** Índice da pessoa na mesa (1, 2, 3...) pra divisão proporcional da conta.
     *  null = pedido coletivo (rateio igual). */
    @Column(name="pessoa_indice") private Integer pessoaIndice;
    /** Nome ou senha pra chamar no balcão ("João", "47"). */
    @Column(name="nome_chamada", length=50) private String nomeChamada;

    @OneToMany(mappedBy="pedido",cascade=CascadeType.ALL,orphanRemoval=true) private List<PedidoItem> itens=new ArrayList<>();
    @CreationTimestamp private LocalDateTime criadoEm;
    @UpdateTimestamp private LocalDateTime atualizadoEm;
    public boolean isAgendado(){return agendadoPara!=null;}
    /** Pedido AGUARDANDO_PAGAMENTO = criado online, ainda não confirmado pelo cliente. */
    /** NA_MESA = pedido entregue na mesa pelo garçom, aguardando fechamento da conta. Só usado em pedidos MESA. */
    public enum Status{AGUARDANDO_PAGAMENTO,PENDENTE,CONFIRMADO,EM_PREPARO,SAIU_ENTREGA,NA_MESA,ENTREGUE,CANCELADO}
    /** BALCAO = cliente faz pedido no caixa, recebe senha, retira na hora. */
    public enum Tipo{DELIVERY,RETIRADA,MESA,BALCAO}
    /** Forma específica usada — guarda histórico do que cliente escolheu. */
    public enum FormaPagamento{PIX,DINHEIRO,CARTAO_MAQUININHA,CARTAO_CREDITO,CARTAO_DEBITO,APPLE_PAY}
    /** ONLINE = paga agora pelo site. NA_ENTREGA = paga quando receber. */
    public enum ModoPagamento{ONLINE,NA_ENTREGA}
}
