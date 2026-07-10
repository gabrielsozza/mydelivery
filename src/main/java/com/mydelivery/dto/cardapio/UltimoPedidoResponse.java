package com.mydelivery.dto.cardapio;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * Payload do endpoint "Pedir novamente" ({@code GET /api/cardapio/{slug}/cliente/{deviceUuid}/ultimo-pedido}).
 *
 * Retorna os dados MÍNIMOS necessários pra o frontend:
 *  1. Preencher automaticamente nome/telefone/endereço no checkout.
 *  2. Renderizar o modal com o resumo do pedido anterior.
 *  3. Reconstruir o carrinho ao clicar "Repetir Pedido", já usando o PREÇO
 *     ATUAL do produto (não o preço histórico) e pulando itens indisponíveis.
 *
 * Cada item traz {@code disponivel} + {@code precoMudou} pra o frontend
 * decidir sozinho o que renderizar e o que avisar ao cliente.
 */
@Data
public class UltimoPedidoResponse {

    // ── Cliente ────────────────────────────────────────────────────────
    private String nome;
    private String telefone;
    private EnderecoDto endereco;

    // ── Pedido ─────────────────────────────────────────────────────────
    private Long pedidoId;
    private LocalDateTime pedidoEm;
    private BigDecimal totalAnterior;   // total pago da última vez (referência histórica)
    private BigDecimal totalAtual;      // total recalculado com preços/disponibilidade de agora
    /** True se algum item mudou de preço em relação ao pedido original. */
    private Boolean algumPrecoMudou;
    /** True se algum item ficou indisponível/removido. */
    private Boolean algumItemRemovido;

    private List<ItemDto> itens;

    @Data
    public static class EnderecoDto {
        private String rua;
        private String numero;
        private String complemento;
        private String bairro;
        private String cidade;
        private String estado;
        private String cep;
        private String referencia;
    }

    @Data
    public static class ItemDto {
        /** ID do PedidoItem original (só pra tracking do frontend). */
        private Long pedidoItemId;
        /** ID do produto no cardápio atual. Pode ser null se produto foi apagado. */
        private Long produtoId;
        private String nome;
        private String fotoUrl;
        private Integer quantidade;
        /** Preço unitário pago no pedido original (referência histórica). */
        private BigDecimal precoOriginal;
        /** Preço unitário vigente HOJE — é o que vai pro carrinho ao repetir. */
        private BigDecimal precoAtual;
        /** True se produto ainda existe E está disponível (Produto.disponivel=true). */
        private Boolean disponivel;
        /** True quando precoAtual != precoOriginal. Frontend pode destacar. */
        private Boolean precoMudou;
        /** Observação do cliente (ex.: "sem cebola, ponto médio"). */
        private String observacao;
    }
}
