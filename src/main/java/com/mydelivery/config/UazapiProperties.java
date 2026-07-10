package com.mydelivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * Configurações da integração com Uazapi (WhatsApp) — substitui Evolution.
 *
 * Autenticação em 2 níveis:
 *  - {@code adminToken} (header {@code admintoken}): válido pra endpoints de
 *    admin do servidor (criar instância, listar todas). É um secret global
 *    da conta Uazapi.
 *  - Token por instância (header {@code token}): retornado no
 *    {@code POST /instance/create}. Usado nas operações que afetam UMA
 *    instância específica (conectar, enviar mensagem, disconnect, status).
 *
 * O token da instância fica em {@code WhatsappInstance.instanceToken}
 * (coluna já existia — mesma estrutura da Evolution).
 */
@Configuration
@ConfigurationProperties(prefix = "mydelivery.uazapi")
@Data
public class UazapiProperties {

    /** Ex.: https://mydeliveryfood.uazapi.com — subdomínio da conta. */
    private String baseUrl = "";

    /** Admin Token do servidor. Header {@code admintoken}. */
    private String adminToken = "";

    /**
     * URL pra onde o Uazapi vai enviar os webhooks. Só usada em logs de
     * diagnóstico — o webhook global é configurado no painel Uazapi pelo
     * dono do MyDelivery, não por instância.
     */
    private String webhookBaseUrl = "";

    /**
     * Timeout total (connect + read). 12s cobre p99 real do Uazapi:
     *  - /instance/create: ~3s
     *  - /instance/connect: ~2s (QR chega no body)
     *  - /send/text: ~1.5s
     */
    private int timeoutMs = 12_000;
}
