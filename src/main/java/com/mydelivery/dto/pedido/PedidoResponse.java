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
    private List<ItemPedidoResponse> itens;

    @Data @Builder
    public static class ItemPedidoResponse {
        private Long id;
        private String nomeProduto;
        private Integer quantidade;
        private BigDecimal precoUnitario;
        private BigDecimal subtotal;
        private String observacao;
    }
}
