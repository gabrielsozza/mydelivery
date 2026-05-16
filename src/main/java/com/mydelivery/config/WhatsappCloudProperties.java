package com.mydelivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * Configurações da integração com Meta WhatsApp Cloud API (oficial).
 *
 * Substitui a integração com Evolution API (Baileys) que era bloqueada
 * por IPs de datacenter. Cloud API roda 100% em infra da Meta — não tem
 * limitação de bloqueio.
 *
 * Docs: https://developers.facebook.com/docs/whatsapp/cloud-api
 */
@Configuration
@ConfigurationProperties(prefix = "mydelivery.whatsapp.cloud")
@Data
public class WhatsappCloudProperties {

    /** Liga/desliga essa integração. Padrão false enquanto não está configurada. */
    private boolean enabled = false;

    /** Versão da Graph API. Atualizar quando Meta lançar nova major estável. */
    private String apiVersion = "v21.0";

    /** ID do número de telefone na Cloud API (15+ dígitos). */
    private String phoneNumberId;

    /** ID da WhatsApp Business Account. */
    private String businessAccountId;

    /** Access token (Bearer) com permissões whatsapp_business_messaging e management. */
    private String accessToken;

    /** App Secret pra validar assinatura HMAC-SHA256 dos webhooks. */
    private String appSecret;

    /** Token que o Meta usa pra verificar o webhook (configurado por nós). */
    private String verifyToken;

    private int timeoutMs = 15000;
}
