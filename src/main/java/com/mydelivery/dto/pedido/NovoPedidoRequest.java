package com.mydelivery.dto.pedido;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class NovoPedidoRequest {

    // ── Identificação ──
    @NotBlank(message = "Slug do restaurante é obrigatório")
    private String slug;                    // vindo do front como "slug"

    // ── Modo de entrega ──
    @NotBlank(message = "Modo é obrigatório")
    private String modo;                    // "delivery" | "retirada" | "mesa"

    // ── Cliente ──
    @NotNull(message = "Dados do cliente são obrigatórios")
    private ClienteDto cliente;

    // ── Endereço (mapa dinâmico por modo) ──
    private Map<String, String> endereco;   // campos variam por modo

    // ── Pagamento ──
    @NotBlank(message = "Forma de pagamento é obrigatória")
    private String pagamento;               // "pix" | "credito" | "debito" | "dinheiro"

    private String troco;                   // preenchido só se pagamento = "dinheiro"

    // ── Itens ──
    @NotEmpty(message = "O pedido deve ter ao menos um item")
    private List<ItemDto> itens;

    // ── Valores ──
    private BigDecimal subtotal;
    private BigDecimal taxa;
    private BigDecimal total;

    // ── Agendamento (opcional) ──
    // Se nulo, o pedido é "para agora". Se preenchido, deve ser uma data/hora futura.
    private LocalDateTime agendadoPara;

    // ── Cupom (opcional) ──
    // Código do cupom aplicado pelo cliente no checkout. Validação completa rodará no service.
    private String cupomCodigo;

    /** "online" = paga agora no site. "entrega" = paga ao receber. Default: entrega. */
    private String modoPagamento;

    // ── Inner classes ──

    @Data
    public static class ClienteDto {
        @NotBlank(message = "Nome do cliente é obrigatório")
        private String nome;

        @NotBlank(message = "Telefone do cliente é obrigatório")
        private String telefone;
    }

    @Data
    public static class ItemDto {
        @NotNull
        private Long produtoId;

        private String nome;

        @NotNull @Min(1)
        private Integer qty;

        private BigDecimal preco;

        private String obs;
    }
}