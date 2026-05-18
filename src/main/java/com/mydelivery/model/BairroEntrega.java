package com.mydelivery.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bairro atendido pelo restaurante com sua taxa de entrega própria.
 *
 * Cada bairro tem taxa independente — a flat-rate antiga em Restaurante.taxaEntrega
 * vira fallback/legado (cardápio público não usa mais). No checkout, o cliente informa
 * o bairro e o backend olha aqui pra decidir se atende + quanto cobrar.
 *
 * Embedded em Restaurante via @ElementCollection na tabela restaurante_bairros.
 * Hibernate (ddl-auto=update) ADD a coluna "taxa" na tabela existente sem dropar dados —
 * bairros antigos ficam com taxa=null e o front trata como "configure a taxa pra esse bairro".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class BairroEntrega {

    /** Nome do bairro como aparece pro cliente. Comparado via normalização (case+acento insensitive). */
    @Column(name = "bairro", length = 120)
    private String nome;

    /** Taxa cobrada quando o cliente seleciona esse bairro. null = não configurado ainda. */
    @Column(name = "taxa", precision = 10, scale = 2)
    private BigDecimal taxa;
}
