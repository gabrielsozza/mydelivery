package com.mydelivery.dto.publico;

import java.math.BigDecimal;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProdutoPublicResponse {
    private Long id;
    private String nome;
    private String desc;
    private String imgUrl;
    private BigDecimal preco;
    private BigDecimal precoOriginal;
    /** Flag de destaque — produto aparece em seção/escala maior no cardápio do cliente. */
    private Boolean destaque;
    /** Tipo do produto: "NORMAL" ou "COMBO" — frontend usa pra renderizar
     *  card especial e abrir modal de slots em vez do modal padrão. */
    private String tipo;
    /** Quando true, o preço é só vitrine (referencial). Cliente paga o valor
     *  da porção escolhida nos complementos. Card mostra "R$ X/{unidade}". */
    private Boolean precoVitrine;
    /** Sufixo de unidade exibido no card ("kg", "100g", "L", "un"). */
    private String unidadePreco;
}