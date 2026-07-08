package com.mydelivery.dto.publico;

import java.math.BigDecimal;
import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RestaurantePublicResponse {
    private String nome;
    private String descricao;
    private String logoUrl;
    private String capaUrl;
    private String corPrimaria;
    private Boolean aberto;
    private Integer tempoEntrega;
    /**
     * Taxa flat antiga — MANTIDA pra compatibilidade de clientes mobile/cache
     * mas o cardápio web NÃO deve mais usar. A taxa real vem por bairro
     * via GET /publico/{slug}/bairros/{nome}/taxa.
     */
    private BigDecimal taxaEntrega;
    private BigDecimal pedidoMinimo;
    /** Valor a partir do qual pedido tem frete grátis. Null = feature off. */
    private BigDecimal freteGratisApartirDe;
    private List<String> modos;
    private List<String> pagamentos;
    /** Se true, cliente que escolher PIX deve receber a chave e mandar comprovante. */
    private Boolean exigirPixAntecipado;
    /** Chave PIX do restaurante exposta ao cliente final. Só preenchida se {@link #exigirPixAntecipado} = true. */
    private String chavePixAntecipado;
    /** Tipo da chave PIX (CPF/CNPJ/EMAIL/TELEFONE/ALEATORIA). Mostrado pro cliente. */
    private String tipoChavePixAntecipado;
    /** Telefone do restaurante (usado pra link de WhatsApp do cliente). */
    private String telefone;
    /**
     * Bairros atendidos com suas taxas. Cliente vê só os nomes no modal "Bairros
     * onde essa loja entrega"; a taxa é revelada no checkout ao informar o endereço.
     */
    private List<BairroAtendidoPublic> bairrosAtendidos;

    @Data
    @Builder
    public static class BairroAtendidoPublic {
        private String nome;
        /** Taxa pode ser null se o dono ainda não configurou — front trata. */
        private BigDecimal taxa;
    }
    /**
     * Public Key do Mercado Pago do restaurante.
     * Enviada ao frontend pra inicializar o SDK e tokenizar cartão no browser —
     * NÃO é segredo (já é "pública" por design no MP).
     * null/blank → restaurante não habilitou pagamento online.
     */
    private String mpPublicKey;
}