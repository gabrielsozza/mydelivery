package com.mydelivery.dto.cupom;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mydelivery.model.Cupom;

import lombok.Data;

/**
 * DTO usado tanto para criar/editar cupom (admin) quanto para retornar dados ao front.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CupomDTO {
    private Long id;
    private String codigo;
    private String tipo;                // PERCENT | FIXO | ITEM_GRATIS
    private BigDecimal valor;
    private String descricao;
    private BigDecimal pedidoMinimo;
    private LocalDateTime validadeInicio;
    private LocalDateTime validadeFim;
    private Integer limiteTotal;
    private Integer limitePorCliente;
    private List<String> modosAplicaveis;
    private Boolean ativo;
    private String origem;              // MANUAL | FIDELIDADE
    private LocalDateTime criadoEm;
    // Só faz sentido em cupom FIDELIDADE — telefone do ganhador + nome
    // resolvido em runtime via lookup no último pedido dele (frontend usa
    // clienteNome primeiro; se null cai pra clienteTelefone; se null "Cliente").
    private String clienteNome;
    private String clienteTelefone;

    public static CupomDTO fromEntity(Cupom c) {
        CupomDTO dto = new CupomDTO();
        dto.id = c.getId();
        dto.codigo = c.getCodigo();
        dto.tipo = c.getTipo() != null ? c.getTipo().name() : null;
        dto.valor = c.getValor();
        dto.descricao = c.getDescricao();
        dto.pedidoMinimo = c.getPedidoMinimo();
        dto.validadeInicio = c.getValidadeInicio();
        dto.validadeFim = c.getValidadeFim();
        dto.limiteTotal = c.getLimiteTotal();
        dto.limitePorCliente = c.getLimitePorCliente();
        dto.modosAplicaveis = c.getModosAplicaveis();
        dto.ativo = c.getAtivo();
        dto.origem = c.getOrigem() != null ? c.getOrigem().name() : null;
        dto.criadoEm = c.getCriadoEm();
        dto.clienteTelefone = c.getTelefoneCliente();
        // clienteNome preenchido depois em CupomService (precisa consultar pedidos).
        return dto;
    }
}
