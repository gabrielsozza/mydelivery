package com.mydelivery.dto.cardapio;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProdutoRequest {

    @NotBlank(message = "Nome é obrigatório")
    private String nome;

    private String descricao;

    @NotNull(message = "Preço é obrigatório")
    @DecimalMin(value = "0.01", message = "Preço deve ser maior que zero")
    private BigDecimal preco;

    /** Preço normal (sem desconto). Quando preenchido, indica que {@link #preco}
     *  é o preço PROMOCIONAL e este é o "de" riscado pro cliente. Null = sem promo. */
    private BigDecimal precoOriginal;

    private Long categoriaId;
    private String fotoUrl;
    private Boolean disponivel = true;
    private Boolean destaque = false;
    /** Produto +18 (bebida alcoólica, tabaco). Cardápio público mostra
     *  badge e exige confirmação de idade antes de adicionar ao carrinho. */
    private Boolean maisDe18 = false;

    /** Quando true, o preço é só REFERENCIAL (vitrine). O valor real cobrado
     *  vem dos complementos (porções). Exemplo: feijão tropeiro R$ 59,99/kg
     *  — cliente escolhe porção 250g/500g/1kg via complemento. */
    private Boolean precoVitrine = false;

    /** Unidade exibida com o preço quando precoVitrine=true.
     *  Valores típicos: kg, g, 100g, L, ml, un, porção. */
    private String unidadePreco;

    /** Se true, exibe "a partir de R$ X" no cardápio público. */
    private Boolean precoAPartirDe;

    /** Dias da semana em que o produto fica ativo (CSV: "QUA,SAB").
     *  NULL/vazio = sempre ativo. */
    private String diasSemanaAtivos;
}