package com.mydelivery.dto.estoque;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mydelivery.model.Insumo;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InsumoDTO {
    private Long id;
    private String nome;
    private String unidade;        // UN | KG | G | L | ML
    private BigDecimal saldoAtual;
    private BigDecimal saldoMinimo;
    private BigDecimal custoMedio;
    private String observacao;
    private Boolean ativo;
    private LocalDateTime criadoEm;

    // Campos calculados — populados nas listagens, ignorados no input
    private String statusAlerta;   // "OK" | "BAIXO" | "ZERADO"
    private Integer usadoEmProdutos; // quantos produtos consomem esse insumo

    public static InsumoDTO fromEntity(Insumo i) {
        InsumoDTO d = new InsumoDTO();
        d.id = i.getId();
        d.nome = i.getNome();
        d.unidade = i.getUnidade() != null ? i.getUnidade().name() : "UN";
        d.saldoAtual = i.getSaldoAtual();
        d.saldoMinimo = i.getSaldoMinimo();
        d.custoMedio = i.getCustoMedio();
        d.observacao = i.getObservacao();
        d.ativo = i.getAtivo();
        d.criadoEm = i.getCriadoEm();
        d.statusAlerta = calcularStatus(i.getSaldoAtual(), i.getSaldoMinimo());
        return d;
    }

    private static String calcularStatus(BigDecimal saldo, BigDecimal minimo) {
        if (saldo == null) return "ZERADO";
        if (saldo.signum() <= 0) return "ZERADO";
        if (minimo != null && saldo.compareTo(minimo) <= 0) return "BAIXO";
        return "OK";
    }
}
