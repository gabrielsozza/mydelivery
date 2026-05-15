package com.mydelivery.config;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Bean único do Cloudinary.
 *
 * Carregamento das credenciais em camadas (primeira fonte com valor vence):
 *  1. Propriedades Spring (@Value) — vêm de application.properties / application-local.properties / .env
 *  2. System.getenv() direto — fallback explícito quando o env não chega pelo
 *     binding do Spring (ex: rodando via IDE em terminal que não exportou as vars).
 *
 * Log de startup mostra qual fonte foi usada (sem expor segredo completo —
 * apenas os últimos 4 chars da key/secret pra debug).
 */
@Slf4j
@Configuration
public class CloudinaryConfig {

    @Value("${mydelivery.cloudinary.cloud-name:}")
    private String cloudNameProp;

    @Value("${mydelivery.cloudinary.api-key:}")
    private String apiKeyProp;

    @Value("${mydelivery.cloudinary.api-secret:}")
    private String apiSecretProp;

    private String cloudName;
    private String apiKey;
    private String apiSecret;

    @PostConstruct
    void resolverCredenciais() {
        // 1. Tenta property (Spring binding)
        cloudName = blankToNull(cloudNameProp);
        apiKey    = blankToNull(apiKeyProp);
        apiSecret = blankToNull(apiSecretProp);

        // 2. Fallback: System.getenv direto (caso o binding falhe ou env não chegue)
        if (cloudName == null) cloudName = System.getenv("CLOUDINARY_CLOUD_NAME");
        if (apiKey == null)    apiKey    = System.getenv("CLOUDINARY_API_KEY");
        if (apiSecret == null) apiSecret = System.getenv("CLOUDINARY_API_SECRET");

        // 3. Log de startup — confirma se carregou e de onde
        if (isConfigured()) {
            log.info("[Cloudinary] ✓ Configurado — cloud='{}', api_key=***{}, secret=***{}",
                    cloudName,
                    sufixo(apiKey),
                    sufixo(apiSecret));
        } else {
            log.warn("[Cloudinary] ✗ NÃO configurado. Defina CLOUDINARY_CLOUD_NAME, "
                    + "CLOUDINARY_API_KEY e CLOUDINARY_API_SECRET via:");
            log.warn("[Cloudinary]   • shell (export VAR=valor) antes de ./mvnw spring-boot:run");
            log.warn("[Cloudinary]   • OU arquivo .env na raiz do projeto (KEY=VALUE por linha)");
            log.warn("[Cloudinary]   • OU application-local.properties");
            log.warn("[Cloudinary] Estado atual — cloud='{}' apiKey={}empty secret={}empty",
                    cloudName == null ? "" : cloudName,
                    apiKey == null ? "" : "not-",
                    apiSecret == null ? "" : "not-");
        }
    }

    @Bean
    public Cloudinary cloudinary() {
        return new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudName == null ? "" : cloudName,
                "api_key",    apiKey    == null ? "" : apiKey,
                "api_secret", apiSecret == null ? "" : apiSecret,
                "secure", true
        ));
    }

    public boolean isConfigured() {
        return cloudName != null && apiKey != null && apiSecret != null;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String sufixo(String s) {
        if (s == null || s.length() < 4) return "????";
        return s.substring(s.length() - 4);
    }
}
