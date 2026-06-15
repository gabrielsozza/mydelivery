package com.mydelivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * Configuração da Meta Conversions API (server-side tracking).
 *
 * Funciona em PARALELO com o Pixel client-side já instalado na landing.
 * Pixel + CAPI = recupera 30-40% de eventos perdidos por iOS 14+, AdBlock
 * e Safari. Match rate sobe de ~60% pra ~90%, Meta otimiza melhor pra
 * Lookalike/Conversion campaigns.
 *
 * Variáveis de ambiente (Railway):
 *   META_PIXEL_ID          = ID do Pixel (mesmo do front: 1503747827953722)
 *   META_CAPI_ACCESS_TOKEN = system-user token gerado no Gerenciador de Eventos
 *                            → Configurações → API de Conversões → Gerar Token
 *   META_CAPI_ATIVO        = true|false (default false — só liga após setar token)
 *   META_CAPI_TEST_EVENT   = código TESTxxxx pra ver eventos em "Test Events"
 *                            (deixa vazio em produção real)
 *
 * Dedup com o Pixel: mesmo event_id (UUID) é mandado nos 2 lados — Meta
 * deduplicates automaticamente. Sem isso, conversão contaria 2x.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mydelivery.meta-capi")
public class MetaCapiProperties {

    /** Endpoint base da Graph API. */
    private String baseUrl = "https://graph.facebook.com/v18.0";

    /** ID do Pixel (mesmo do front-end). */
    private String pixelId = "";

    /** System user access token com permissão ads_management. */
    private String accessToken = "";

    /** Se false, MetaCapiService não envia nada — útil pra dev local. */
    private boolean ativo = false;

    /** Código de teste TESTxxxxx — mostra eventos só em "Test Events" sem
     *  contar em campanhas reais. Vazio = eventos vão pra produção. */
    private String testEventCode = "";

    /** Timeout das chamadas pra Graph API. */
    private int timeoutMs = 10_000;
}
