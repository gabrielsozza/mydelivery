package com.mydelivery.dto.fidelidade;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Status do programa de fidelidade para um cliente específico (visão do checkout).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusClienteDTO {
    private boolean programaAtivo;
    private int saldoPontos;
    private int pontosParaRecompensa;
    private int pontosFaltando;
    private String tipoRecompensa;          // DESCONTO_PERCENT | DESCONTO_FIXO | ITEM_GRATIS
    private BigDecimal valorRecompensa;
    private String descricaoRecompensa;
    private BigDecimal valorPorPonto;       // pra calcular preview de quantos pontos esse pedido vai dar

    // Caso o cliente tenha um cupom de fidelidade não usado:
    private String cupomDisponivel;         // código (ex: FID-AB12CD), null se não tem
}
