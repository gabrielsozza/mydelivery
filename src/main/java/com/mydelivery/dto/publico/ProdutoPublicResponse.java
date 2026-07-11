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
    /** Se true, cardápio público mostra prefixo "a partir de" antes do preço.
     *  Útil pra produtos com complementos obrigatórios que somam ao preço final
     *  (ex: feijoada base + adicionais). Diferente de precoVitrine — este é
     *  cosmético, o preço base ainda é o mínimo cobrado. */
    private Boolean precoAPartirDe;
    /** Produto +18 (bebida alcoólica, tabaco). Cardápio público pinta badge
     *  vermelho no card e exige confirmação de idade antes de adicionar ao
     *  carrinho. Não bloqueia a EXIBIÇÃO — só a compra. */
    private Boolean maisDe18;
}