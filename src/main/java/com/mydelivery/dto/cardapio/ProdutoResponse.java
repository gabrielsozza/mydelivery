package com.mydelivery.dto.cardapio;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProdutoResponse {
    private Long id;
    private String nome;
    private String descricao;
    private BigDecimal preco;
    /** Preço "de" (riscado no cliente). Null = sem promo. */
    private BigDecimal precoOriginal;
    private String fotoUrl;
    private Boolean disponivel;
    private Boolean destaque;
    private Long categoriaId;
    private String categoriaNome;
    /** Posição do produto dentro da categoria (menor = aparece primeiro). */
    private Integer ordem;
    /** "NORMAL" (default) ou "COMBO". Painel e cardápio público usam pra
     *  abrir o modal certo (combo abre fluxo com slots e grupos próprios). */
    private String tipo;
}