package com.mydelivery.dto.estoque;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mydelivery.model.Compra;

import lombok.Data;

/**
 * DTO usado tanto pra INPUT (registrar compra) quanto OUTPUT (listagem).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompraDTO {
    private Long id;
    private String fornecedor;
    private String notaFiscal;
    private LocalDateTime dataCompra;
    private BigDecimal total;
    private String observacao;
    private List<Item> itens;
    private LocalDateTime criadoEm;

    @Data
    public static class Item {
        private Long insumoId;
        private String insumoNome;
        private String unidade;
        private BigDecimal quantidade;
        private BigDecimal custoUnitario;
        private BigDecimal subtotal;
    }

    public static CompraDTO fromEntity(Compra c) {
        CompraDTO d = new CompraDTO();
        d.id = c.getId();
        d.fornecedor = c.getFornecedor();
        d.notaFiscal = c.getNotaFiscal();
        d.dataCompra = c.getDataCompra();
        d.total = c.getTotal();
        d.observacao = c.getObservacao();
        d.criadoEm = c.getCriadoEm();
        if (c.getItens() != null) {
            d.itens = c.getItens().stream().map(ci -> {
                Item i = new Item();
                i.setInsumoId(ci.getInsumo().getId());
                i.setInsumoNome(ci.getInsumo().getNome());
                i.setUnidade(ci.getInsumo().getUnidade() != null ? ci.getInsumo().getUnidade().name() : "UN");
                i.setQuantidade(ci.getQuantidade());
                i.setCustoUnitario(ci.getCustoUnitario());
                i.setSubtotal(ci.getSubtotal());
                return i;
            }).toList();
        }
        return d;
    }
}
