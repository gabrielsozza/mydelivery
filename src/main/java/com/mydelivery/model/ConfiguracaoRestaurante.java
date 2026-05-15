package com.mydelivery.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "configuracoes_restaurante")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfiguracaoRestaurante {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    private String pixTipoChave;
    private String pixChave;

    @Column(precision = 10, scale = 2)
    private BigDecimal taxaEntrega = BigDecimal.ZERO;

    @Column(precision = 10, scale = 2)
    private BigDecimal pedidoMinimo = BigDecimal.ZERO;

    private Integer tempoEntregaMin = 30;
    private Integer tempoEntregaMax = 60;

    private Boolean aceitaPix = true;
    private Boolean aceitaDinheiro = true;
    private Boolean aceitaCartaoMaquininha = true;

    private Integer tempoAbandonoMinutos = 5;

    private Boolean aberto = false;
    private String horarioAbertura;
    private String horarioFechamento;

    // ── Mercado Pago (credenciais do tenant) ──
    /**
     * Access Token do Mercado Pago do restaurante.
     * Gerado em https://www.mercadopago.com.br/developers/panel.
     * Usado no Authorization: Bearer das chamadas a /v1/payments.
     * Guardado em TEXT porque pode ter > 255 chars dependendo do escopo OAuth.
     */
    @Column(name = "mp_access_token", columnDefinition = "TEXT")
    private String mpAccessToken;

    /**
     * Public Key do MP — enviada ao frontend pra inicializar o SDK MercadoPago.js
     * e tokenizar o cartão direto no browser (não passa pelo nosso backend).
     */
    @Column(name = "mp_public_key", length = 120)
    private String mpPublicKey;

    /**
     * Segredo usado pra validar a assinatura HMAC-SHA256 dos webhooks.
     * Configurado no painel do MP em Webhooks → "Secret signature".
     */
    @Column(name = "mp_webhook_secret", length = 120)
    private String mpWebhookSecret;
}