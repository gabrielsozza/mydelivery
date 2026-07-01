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
    // Timeout curto pra I/O externo — se Evolution não respondeu em 4s, é anormal.
    // Antes 15s: thread do Tomcat ficava presa esperando, gerando cascata sob rush.
    // Falha rápido → libera thread pra requests reais. Override via env
    // MYDELIVERY_EVOLUTION_TIMEOUT_MS se precisar aumentar.
    private int timeoutMs = 4000;

    private Bot bot = new Bot();

    /**
     * Proxy residencial sticky (Proxy-Seller) injetado em cada criação de instância.
     * Quando preenchido, o Baileys da Evolution conecta ao WhatsApp através do proxy —
     * essencial pra evitar bloqueio do WhatsApp (IPs de datacenter são banidos
     * agressivamente).
     *
     * Se {@code host} estiver vazio, o proxy é desabilitado pra todas as novas instâncias.
     *
     * @deprecated Use {@link #pools} pra distribuir N lojas em vários IPs residenciais.
     *             Mantido aqui por retrocompat — fallback quando a instância não tem
     *             proxyPool atribuído.
     */
    @Deprecated
    private Proxy proxy = new Proxy();

    /**
     * Pool de proxies residenciais — distribui N instâncias em vários IPs pra
     * reduzir densidade (menos números por IP = menos shadow ban) e dividir a
     * banda mensal por mais "buckets".
     *
     * Formato da URL: {@code http://user:pass@host:port} (ou socks5://, https://).
     * Spring converte env vars MYDELIVERY_EVOLUTION_POOLS_A automaticamente
     * em {@code pools.get("A")}.
     *
     * Chave-padrão sugerida (mas livre): "A", "B", "C", "D"... cada uma com URL
     * de um IP residencial diferente. A instância referencia o pool por
     * {@code WhatsappInstance.proxyPool}.
     */
    private java.util.Map<String, String> pools = new java.util.HashMap<>();

    /**
     * Resolve a URL do pool e parseia em {@link Proxy}. Devolve {@code null}
     * se a chave não existe ou a URL é inválida. Usado pelo EvolutionClient
     * pra montar o body do /instance/create.
     */
    public Proxy resolverPool(String poolKey) {
        if (poolKey == null || poolKey.isBlank()) return null;
        String raw = pools.get(poolKey);
        if (raw == null || raw.isBlank()) return null;
        try {
            java.net.URI u = java.net.URI.create(raw.trim());
            String userInfo = u.getUserInfo();
            String username = "";
            String password = "";
            if (userInfo != null && userInfo.contains(":")) {
                int idx = userInfo.indexOf(':');
                username = userInfo.substring(0, idx);
                password = userInfo.substring(idx + 1);
            }
            Proxy p = new Proxy();
            p.setHost(u.getHost());
            p.setPort(u.getPort() > 0 ? u.getPort() : 80);
            p.setProtocol(u.getScheme() != null ? u.getScheme() : "http");
            p.setUsername(username);
            p.setPassword(password);
            return p.isAtivo() ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

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
