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
    private BigDecimal taxaEntrega;
    private BigDecimal pedidoMinimo;
    private List<String> modos;
    private List<String> pagamentos;
    /** Lista de bairros atendidos pela loja — exibida no cardápio público. */
    private List<String> bairrosAtendidos;
    /**
     * Public Key do Mercado Pago do restaurante.
     * Enviada ao frontend pra inicializar o SDK e tokenizar cartão no browser —
     * NÃO é segredo (já é "pública" por design no MP).
     * null/blank → restaurante não habilitou pagamento online.
     */
    private String mpPublicKey;
}