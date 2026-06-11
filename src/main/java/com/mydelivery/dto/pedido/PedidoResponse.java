package com.mydelivery.dto.pedido;
import com.mydelivery.model.Pedido;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder
public class PedidoResponse {
    private Long id;
    private Pedido.Status status;
    private Pedido.Tipo tipo;
    private Pedido.FormaPagamento formaPagamento;
    private Pedido.ModoPagamento modoPagamento;
    private Boolean pago;
    private LocalDateTime pagoEm;
    private String nomeCliente;
    private String telefoneCliente;
    private String enderecoEntrega;
    private String observacao;
    private BigDecimal subtotal;
    private BigDecimal taxaEntrega;
    private BigDecimal total;
    private LocalDateTime criadoEm;
    private LocalDateTime agendadoPara;
    private String restauranteNome;
    /** Telefone do restaurante — pra botão WhatsApp na tela de acompanhamento. */
    private String restauranteTelefone;
    /** Tempo estimado (min) — usado na tela de acompanhamento como "30 min" ou "30-50 min". */
    private Integer restauranteTempoEntrega;
    private Integer restauranteTempoEntregaMax;
    private Long entregadorId;
    private String entregadorNome;
    // ── Pedido de mesa (presencial) ──
    private Long mesaId;
    private String mesaNome;
    private String mesaSlug;
    private String nomeClienteMesa;
    /** Nome digitado no balcao (POS). Distinto de nomeClienteMesa (QR mesa)
     *  e da entidade Cliente. nomeCliente ja faz fallback pra ele, mas
     *  expor explicito ajuda telas que querem distinguir origem. */
    private String nomeChamada;

    /** Canal onde o pedido foi criado. "MYDELIVERY" (default) ou "IFOOD".
     *  Usado pelo frontend pra mostrar a logo correta no card e drawer. */
    private String origem;

    /** displayId curto do iFood (visível na resposta apenas pra pedidos IFOOD).
     *  É o número que o cliente vê no app do iFood — útil pro restaurante
     *  responder dúvida do cliente referenciando o mesmo número. */
    private String ifoodDisplayId;

    private List<ItemPedidoResponse> itens;

    /**
     * Divisão de pagamentos por pessoa (preenchido quando a sessão da mesa
     * foi fechada com "Dividir conta"). Vazio/null = pagamento único —
     * neste caso a informação está em {@link #formaPagamento}.
     */
    private List<DivisaoPagamentoResponse> divisaoPagamentos;

    /** True se a sessão da mesa incluiu 10% de serviço no fechamento. */
    private Boolean incluiuServico;

    /** Valor total efetivamente cobrado na sessão da mesa (com ou sem
     *  10% de serviço). Distinto de {@link #total} do pedido — pode ser
     *  maior se houver mais de um pedido na mesa. */
    private BigDecimal valorCobradoSessao;

    @Data @Builder
    public static class ItemPedidoResponse {
        private Long id;
        private String nomeProduto;
        private Integer quantidade;
        private BigDecimal precoUnitario;
        private BigDecimal subtotal;
        private String observacao;
    }

    @Data @Builder
    public static class DivisaoPagamentoResponse {
        /** 1..N. Pessoa 1 = primeira pessoa, etc. */
        private Integer pessoa;
        /** Valor que essa pessoa pagou (já inclui parte do 10% se houver). */
        private BigDecimal total;
        /** Forma de pagamento. Valores: DINHEIRO, PIX, CARTAO_CREDITO,
         *  CARTAO_DEBITO, CARTAO_MAQUININHA. */
        private String formaPagamento;
    }
}
