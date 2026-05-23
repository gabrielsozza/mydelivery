package com.mydelivery.service.cardapio.importacao.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resultado normalizado da extração. Mesmo formato pra todos os providers —
 * o frontend não precisa saber qual foi usado.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultadoImport {
    /** Identificador do provider que conseguiu extrair ("anotaai", "ifood", "json-ld", etc). */
    private String provider;
    /** 0-100 — confiança geral. Calculado pela média dos scores dos produtos + bônus por provider. */
    private int score;
    @Builder.Default
    private List<CategoriaImportada> categorias = new ArrayList<>();
    /** Mensagens sobre o que faltou ou foi parcialmente extraído. Mostradas ao usuário no preview. */
    @Builder.Default
    private List<String> avisos = new ArrayList<>();
    /** URL original que foi importada — útil pra logging/debug. */
    private String urlOrigem;

    /** Soma de produtos em todas as categorias. */
    public int getTotalProdutos() {
        return categorias.stream().mapToInt(c -> c.getProdutos() == null ? 0 : c.getProdutos().size()).sum();
    }
}
