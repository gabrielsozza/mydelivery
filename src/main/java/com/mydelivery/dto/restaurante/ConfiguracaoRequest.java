package com.mydelivery.dto.restaurante;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfiguracaoRequest {

    private String nome;
    private String slug;
    private String descricao;
    private String telefone;
    private String endereco;
    private String cidade;
    private String estado;
    private String corPrimaria;
    private String temaCardapio;        // "claro" | "escuro"

    private Boolean aberto;
    private Integer tempoEntrega;
    private BigDecimal taxaEntrega;
    private BigDecimal pedidoMinimo;
    private Integer qtdMesas;

    private List<String> modos;
    private List<String> pagamentos;
    /** Horários de funcionamento — { seg: {aberto,abertura,fechamento}, ... } */
    private Object horarios;
    /**
     * Bairros atendidos com taxa por bairro. Cada item é {nome, taxa}.
     * Taxa null = dono ainda não definiu (front avisa).
     */
    private List<BairroDTO> bairrosAtendidos;

    private AgendamentoDTO agendamento;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BairroDTO {
        private String nome;
        private BigDecimal taxa;
    }

    // ── Credenciais Mercado Pago (multi-tenant) ──
    // Mantidas opcionais: restaurante pode operar sem MP (recebe só PIX manual/dinheiro).
    private String mpAccessToken;
    private String mpPublicKey;
    private String mpWebhookSecret;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgendamentoDTO {
        private Boolean ativo;
        private Integer antecedencia;
        private Integer intervalo;
        private List<String> slots;
    }
}