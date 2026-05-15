package com.mydelivery.dto.cardapio;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Estrutura retornada pelo backend após analisar o arquivo CSV/Excel.
 * Cliente revisa, edita e confirma — só então é salvo no banco.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportacaoPreviewDTO {

    /** Plataforma detectada (Anotaaí, Olaclick, Genérico). */
    private String plataformaDetectada;

    /** Total de linhas válidas + inválidas. */
    private int totalLinhas;
    private int linhasInvalidas;

    /** Avisos / mensagens informativas pro cliente. */
    private List<String> avisos;

    /** Categorias com seus produtos, prontas pra cliente editar. */
    private List<CategoriaImport> categorias;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoriaImport {
        private String nome;
        private List<ProdutoImport> produtos;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProdutoImport {
        private String nome;
        private String descricao;
        private BigDecimal preco;
        private String imagemUrl;
        private Boolean importar;   // checkbox - cliente pode desmarcar antes de salvar
    }
}
