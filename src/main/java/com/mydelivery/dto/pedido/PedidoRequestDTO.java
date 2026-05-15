package com.mydelivery.dto.pedido;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

@Data
public class PedidoRequestDTO {

    private String slug;
    private String modo;           // DELIVERY, RETIRADA, MESA

    private ClienteDTO cliente;
    private EnderecoDTO endereco;

    private String pagamento;
    private String troco;

    private List<ItemDTO> itens;

    private BigDecimal subtotal;
    private BigDecimal taxa;
    private BigDecimal total;

    // null = pedido imediato | preenchido = agendado
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime agendadoPara;

    @Data
    public static class ClienteDTO {
        private String nome;
        private String telefone;
    }

    @Data
    public static class EnderecoDTO {
        private String rua;
        private String numero;
        private String complemento;
        private String bairro;
        private String referencia;
        private String mesa;
    }

    @Data
    public static class ItemDTO {
        private Long produtoId;
        private String nome;
        private Integer qty;
        private BigDecimal preco;
        private String obs;
    }
}