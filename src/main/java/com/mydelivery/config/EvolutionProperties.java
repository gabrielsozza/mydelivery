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

    /**
     * Proxy residencial sticky (Proxy-Seller) injetado em cada criação de instância.
     * Quando preenchido, o Baileys da Evolution conecta ao WhatsApp através do proxy —
     * essencial pra evitar bloqueio do WhatsApp (IPs de datacenter são banidos
     * agressivamente).
     *
     * Se {@code host} estiver vazio, o proxy é desabilitado pra todas as novas instâncias.
     */
    private Proxy proxy = new Proxy();

    @Data
    public static class Bot {
        /** Quantos segundos esperar antes de responder o mesmo número novamente. Anti-flood. */
        private int throttleSegundos = 30;
        /** Modo silêncio após cliente pedir atendente: bot fica quieto por N minutos. */
        private int silencioMinutos = 30;
    }

    @Data
    public static class Proxy {
        private String host = "";
        private int port = 0;
        /** http | https | socks5 */
        private String protocol = "http";
        private String username = "";
        private String password = "";

        public boolean isAtivo() {
            return host != null && !host.isBlank() && port > 0;
        }
    }
}
