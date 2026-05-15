package com.mydelivery.dto.estoque;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mydelivery.model.MovimentacaoEstoque;

import lombok.Data;

/**
 * Usado tanto pra INPUT (registrar ajuste manual) quanto pra OUTPUT (histórico).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MovimentacaoDTO {
    private Long id;
    private Long insumoId;
    private String insumoNome;
    private String unidade;
    private String tipo;            // ENTRADA_INICIAL | ENTRADA_COMPRA | AJUSTE | PERDA | SAIDA_VENDA | ENTRADA_REVERSAO
    private String motivoPerda;     // só preenchido se tipo = PERDA
    private BigDecimal quantidade;  // positivo = entrada, negativo = saída
    private BigDecimal saldoApos;
    private Long pedidoId;
    private Long compraId;
    private String observacao;
    private LocalDateTime criadoEm;

    public static MovimentacaoDTO fromEntity(MovimentacaoEstoque m) {
        MovimentacaoDTO d = new MovimentacaoDTO();
        d.id = m.getId();
        d.insumoId = m.getInsumo() != null ? m.getInsumo().getId() : null;
        d.insumoNome = m.getInsumo() != null ? m.getInsumo().getNome() : null;
        d.unidade = m.getInsumo() != null && m.getInsumo().getUnidade() != null
                ? m.getInsumo().getUnidade().name() : "UN";
        d.tipo = m.getTipo() != null ? m.getTipo().name() : null;
        d.motivoPerda = m.getMotivoPerda() != null ? m.getMotivoPerda().name() : null;
        d.quantidade = m.getQuantidade();
        d.saldoApos = m.getSaldoApos();
        d.pedidoId = m.getPedidoId();
        d.compraId = m.getCompraId();
        d.observacao = m.getObservacao();
        d.criadoEm = m.getCriadoEm();
        return d;
    }
}
