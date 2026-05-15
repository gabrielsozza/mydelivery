package com.mydelivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * Configurações globais da integração Mercado Pago.
 * Credenciais (access token, public key, webhook secret) NÃO ficam aqui —
 * são por restaurante, vivem em ConfiguracaoRestaurante. Aqui só vão constantes
 * que valem pra todos os tenants (base URL, timeouts, URL pública do webhook).
 */
@Configuration
@ConfigurationProperties(prefix = "mydelivery.mercadopago")
@Data
public class MercadoPagoProperties {

    /** Endpoint base da API do MP. Override apenas pra testes (sandbox/mock). */
    private String baseUrl = "https://api.mercadopago.com";

    /** Timeout de conexão+leitura em ms. MP é rápido, mas PIX pode pegar até alguns segundos. */
    private int timeoutMs = 15000;

    /**
     * URL pública onde o MP vai bater pra notificar mudanças de status.
     * Em prod: https://api.seudominio.com/api/webhooks/mercadopago.
     * Em dev: usar ngrok ou similar pra expor a porta 8080.
     */
    private String webhookUrl = "http://localhost:8080/api/webhooks/mercadopago";

    /**
     * Tempo de expiração do QR Code PIX em minutos.
     * Após esse prazo, o cliente precisa gerar um novo.
     */
    private int pixExpiracaoMin = 30;
}
