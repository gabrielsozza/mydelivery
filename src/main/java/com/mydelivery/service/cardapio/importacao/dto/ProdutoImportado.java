package com.mydelivery.service.cardapio.importacao.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Produto bruto extraído de qualquer provider de importação. Os DTOs daqui são
 * intencionalmente "soltos" (Strings, BigDecimal nullable) — eles representam o
 * que foi possível extrair, não o estado validado. A normalização e validação
 * acontecem no {@code ImportNormalizer} antes de virar Produto persistido.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProdutoImportado {
    private String nome;
    private String descricao;
    private BigDecimal preco;
    /** URL original da imagem no site fonte. Só é baixada pro Cloudinary no confirm. */
    private String imagemUrl;
    /** Categoria sugerida pelo provider (pode ser sobrescrita pelo agrupador). */
    private String categoriaSugerida;
    /** 0-100 — quão "completo" esse item ficou. Usado pra calcular score geral. */
    @Builder.Default
    private int score = 0;
}
