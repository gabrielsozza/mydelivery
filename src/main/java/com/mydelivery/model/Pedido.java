package com.mydelivery.model;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;

@Entity
@Table(name = "pedidos", indexes = {
    // Painel: listar pedidos do restaurante recentes (mais comum).
    @jakarta.persistence.Index(name = "ix_pedido_rest_criado",
            columnList = "restaurante_id, criado_em"),
    // Painel filtrado por status (ex: ABERTOS, EM_PREPARO).
    @jakarta.persistence.Index(name = "ix_pedido_rest_status_criado",
            columnList = "restaurante_id, status, criado_em"),
    // Comanda da mesa (cliente final + painel).
    @jakarta.persistence.Index(name = "ix_pedido_mesa_status",
            columnList = "mesa_id, status"),
    // Garçom: pedidos de uma sessão (Mesa aberta).
    @jakarta.persistence.Index(name = "ix_pedido_sessao_criado",
            columnList = "sessao_id, criado_em")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Pedido {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne @JoinColumn(name="restaurante_id",nullable=false) private Restaurante restaurante;
    /** Cliente do pedido. Excluído de equals/hashCode/toString porque
     *  {@link Cliente#getUltimoPedido()} aponta de volta pra cá — o ciclo
     *  bidirecional derrubava o criar-pedido com StackOverflowError no
     *  {@code @Data} do Lombok (log Railway 10/jul/2026). */
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
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

    // ── Origem do pedido (integrações com marketplaces) ──
    /** Canal onde o pedido foi criado. Default MYDELIVERY (criado pelo nosso
     *  cardápio/painel). IFOOD significa que veio da Order API do iFood via polling. */
    @Enumerated(EnumType.STRING)
    @Column(name="origem", length=20)
    @Builder.Default
    private Origem origem = Origem.MYDELIVERY;

    /** ID do pedido no sistema do iFood (UUID). Null pra pedidos não-iFood.
     *  Único — evita duplicar se o polling pegar o mesmo evento 2x. */
    @Column(name="ifood_order_id", length=40, unique = true)
    private String ifoodOrderId;

    /** displayId do iFood (ex: "1234") — número curto que o cliente vê no app
     *  iFood. Útil pra suporte/atendimento referenciar o pedido pelo mesmo
     *  número que o cliente final está vendo. */
    @Column(name="ifood_display_id", length=20)
    private String ifoodDisplayId;

    @OneToMany(mappedBy="pedido",cascade=CascadeType.ALL,orphanRemoval=true) private List<PedidoItem> itens=new ArrayList<>();
    @CreationTimestamp private LocalDateTime criadoEm;
    @UpdateTimestamp private LocalDateTime atualizadoEm;
    public boolean isAgendado(){return agendadoPara!=null;}
    /** Pedido AGUARDANDO_PAGAMENTO = criado online, ainda não confirmado pelo cliente. */
    /** NA_MESA = pedido entregue na mesa pelo garçom, aguardando fechamento da conta. Só usado em pedidos MESA. */
    /** PRONTO = cozinha finalizou, aguardando retirada pelo cliente (balcão) ou
     *  saída de entregador. Usado pelo painel-chamada da TV pra mostrar "Senha 47". */
    public enum Status{AGUARDANDO_PAGAMENTO,PENDENTE,CONFIRMADO,EM_PREPARO,PRONTO,SAIU_ENTREGA,NA_MESA,ENTREGUE,CANCELADO}
    /** BALCAO = cliente faz pedido no caixa, recebe senha, retira na hora. */
    public enum Tipo{DELIVERY,RETIRADA,MESA,BALCAO}
    /** Forma específica usada — guarda histórico do que cliente escolheu. */
    /** PENDENTE = pedido criado sem cobranca (cliente vai pagar depois — uso comum em
     *  balcao quando dono quer fechar conjunto no fim do dia/da visita). Diferente
     *  de Status.AGUARDANDO_PAGAMENTO (esse e' fluxo de PIX online esperando webhook
     *  do Mercado Pago). PENDENTE significa "ainda nao escolheu como pagar". */
    public enum FormaPagamento{PIX,DINHEIRO,CARTAO_MAQUININHA,CARTAO_CREDITO,CARTAO_DEBITO,APPLE_PAY,PENDENTE}
    /** ONLINE = paga agora pelo site. NA_ENTREGA = paga quando receber. */
    public enum ModoPagamento{ONLINE,NA_ENTREGA}

    /** Canal de origem do pedido. Permite distinguir visualmente no painel
     *  (badge com logo) e tratar fluxos específicos (ex.: pedido iFood já vem
     *  pago, não precisa de "marcar como pago"). */
    public enum Origem { MYDELIVERY, IFOOD }
}
