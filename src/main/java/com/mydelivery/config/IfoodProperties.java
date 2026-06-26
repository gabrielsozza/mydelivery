package com.mydelivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * Configuração da integração com a Order API do iFood (developer.ifood.com.br).
 *
 * Variáveis de ambiente (Railway):
 *   IFOOD_CLIENT_ID         = client_id do app criado no portal de developer
 *   IFOOD_CLIENT_SECRET     = client_secret (NUNCA logar)
 *   IFOOD_BASE_URL          = https://merchant-api.ifood.com.br (default)
 *   IFOOD_POLLING_ATIVO     = true|false (default false; só liga quando app for homologado)
 *
 * Como funciona o OAuth do iFood:
 *  1. App MyDelivery tem client_id/secret fixos (esses props).
 *  2. Cada restaurante autoriza o app dentro do Gestor de Pedidos dele.
 *  3. iFood devolve um merchantId (UUID) que persistimos em
 *     Restaurante.ifoodMerchantId.
 *  4. Pra chamar API, geramos access_token via client_credentials usando o
 *     client_id/secret do app — esse token serve pra TODOS os merchants
 *     autorizados (não é per-merchant).
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mydelivery.ifood")
public class IfoodProperties {

    /** Base URL da Order API do iFood. */
    private String baseUrl = "https://merchant-api.ifood.com.br";

    /** client_id do aplicativo MyDelivery no portal developer.ifood.com.br */
    private String clientId = "";

    /** client_secret do aplicativo. */
    private String clientSecret = "";

    /** Se false, IfoodPollingJob não roda — útil enquanto o app não foi
     *  homologado pelo iFood ou pra debug local. */
    private boolean pollingAtivo = false;

    /** Intervalo de polling em segundos (mínimo recomendado pelo iFood: 30s). */
    private int pollingIntervaloSegundos = 30;

    /** Timeout de chamadas HTTP em ms. */
    private int timeoutMs = 15_000;

    /**
     * Modo homologação: quando TRUE, auto-cancela TODO pedido iFood recebido
     * após {@link #homologacaoAutoCancelDelaySec} segundos da confirmação.
     * Necessário pro cenário "Pedido Cancelado" da homologação automatizada
     * (TOQAN), que espera o restaurante enviar requestCancellation sem
     * intervenção humana.
     *
     * IMPORTANTE: desligar (false) ANTES de pedidos reais de cliente real
     * começarem a chegar — senão cancela tudo!
     */
    private boolean homologacaoMode = false;

    /** Segundos após PLC pra disparar o auto-cancel em homologação. */
    private int homologacaoAutoCancelDelaySec = 45;
}
