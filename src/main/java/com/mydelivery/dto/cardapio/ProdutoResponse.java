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
    /** +18 (bebida alcoólica, tabaco). Frontend pinta badge no card e exige
     *  confirmação de idade no cardápio público. */
    private Boolean maisDe18;
    private Long categoriaId;
    private String categoriaNome;
    /** Posição do produto dentro da categoria (menor = aparece primeiro). */
    private Integer ordem;
    /** "NORMAL" (default) ou "COMBO". Painel e cardápio público usam pra
     *  abrir o modal certo (combo abre fluxo com slots e grupos próprios). */
    private String tipo;
    /** Quando true, o preço é só referencial (vitrine "R$ X/kg"). Cliente paga
     *  pelo valor das porções escolhidas nos complementos, não pelo preço base. */
    private Boolean precoVitrine;
    /** Unidade exibida com o preço (ex: "kg", "100g", "un", "porção"). */
    private String unidadePreco;
    /** Se true, cardápio público mostra "a partir de R$ X" antes do preço. */
    private Boolean precoAPartirDe;
    /** CSV de dias da semana ativos ("SEG,QUA,SAB") ou null pra sempre ativo. */
    private String diasSemanaAtivos;

    /** Grupos de complementos (sabores, adicionais, variações). Cada grupo tem
     *  min/máx escolhas + lista de itens com preço próprio. Usado pelo garçom
     *  e pelo cliente pra abrir modal de personalização quando o produto tem
     *  variações. Vazio (ou null) = produto simples, adiciona direto. */
    private java.util.List<GrupoComplementoResponse> gruposComplemento;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class GrupoComplementoResponse {
        private Long id;
        private String nome;
        private Boolean obrigatorio;
        private Integer minEscolhas;
        private Integer maxEscolhas;
        /** SOMA (preço soma ao produto) ou MAIOR (preço do produto vira o mais caro). */
        private String modoPreco;
        private Boolean permitirNenhuma;
        private java.util.List<ItemComplementoResponse> itens;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ItemComplementoResponse {
        private Long id;
        private String nome;
        private String descricao;
        private BigDecimal precoAdicional;
        private Integer maxSelecoes;
    }
}