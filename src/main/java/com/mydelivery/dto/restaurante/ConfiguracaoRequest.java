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
    private String endereco;     // legado (mantido p/ retrocompat)
    private String rua;
    private String numero;
    private String bairro;
    private String cep;
    private String cidade;
    private String estado;
    /** CPF OU CNPJ — um dos dois (restaurantes pequenos costumam só ter CPF). */
    private String cnpj;
    private String cpf;
    private String corPrimaria;
    private String temaCardapio;        // "claro" | "escuro"

    private Boolean aberto;
    private Integer tempoEntrega;
    private Integer tempoEntregaMax;
    private BigDecimal taxaEntrega;
    private BigDecimal pedidoMinimo;
    private Integer qtdMesas;

    private List<String> modos;
    private List<String> pagamentos;
    /** Se true, cliente que escolher PIX recebe a chave e é orientado a pagar
     *  antes e mandar comprovante no WhatsApp. Combina com "pix" em pagamentos. */
    private Boolean exigirPixAntecipado;
    /** Chave PIX (CPF/CNPJ/email/telefone/aleatória). Usada quando exigirPixAntecipado. */
    private String chavePixAntecipado;
    /** Tipo da chave PIX: "CPF" | "CNPJ" | "EMAIL" | "TELEFONE" | "ALEATORIA". */
    private String tipoChavePixAntecipado;
    /** Horários de funcionamento — { seg: {aberto,abertura,fechamento}, ... } */
    private Object horarios;
    /** Toggle: abre a loja automaticamente no horário cadastrado. */
    private Boolean aberturaAutomatica;
    /** Toggle: cliente recebe botão "Confirmar via WhatsApp" após finalizar pedido. */
    private Boolean confirmacaoWhatsappAtiva;
    /** Toggle: para de aceitar pedidos N min antes do fechamento. */
    private Boolean pararPedidosAntesFechamento;
    /** Quantos minutos antes do fechamento bloquear pedidos novos. */
    private Integer minutosAntesFechamento;
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