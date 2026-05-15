package com.mydelivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * Configurações da integração com Evolution API (WhatsApp).
 *
 * apiKey é global (válida pra criar/listar instâncias). Após criar instância,
 * a Evolution devolve um instance-token específico que é guardado por restaurante
 * em WhatsappInstance.instanceToken — usado pra autenticar operações daquela
 * instância (envio de mensagem, etc.) sem expor a chave global.
 */
@Configuration
@ConfigurationProperties(prefix = "mydelivery.evolution")
@Data
public class EvolutionProperties {

    private String baseUrl = "http://localhost:8081";
    private String apiKey;
    private String webhookBaseUrl = "http://localhost:8080";
    private int timeoutMs = 15000;

    private Bot bot = new Bot();

    @Data
    public static class Bot {
        /** Quantos segundos esperar antes de responder o mesmo número novamente. Anti-flood. */
        private int throttleSegundos = 30;
        /** Modo silêncio após cliente pedir atendente: bot fica quieto por N minutos. */
        private int silencioMinutos = 30;
    }
}
