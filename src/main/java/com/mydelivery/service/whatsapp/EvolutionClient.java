package com.mydelivery.service.whatsapp;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.mydelivery.config.EvolutionProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * HTTP client pra Evolution API (WhatsApp). Mesmo padrão do MercadoPagoClient.
 *
 * Auth: header "apikey" — em criação/listagem usamos a chave global; em operações
 * de instância específica (envio etc.), usamos o token devolvido pela Evolution
 * no /instance/create. Isso evita expor a chave global em todas as operações
 * e isola permissões por tenant.
 *
 * Endpoints cobertos:
 *  - POST /instance/create          (cria + já configura webhook)
 *  - GET  /instance/connect/{name}  (busca QR)
 *  - GET  /instance/connectionState/{name}
 *  - DELETE /instance/logout/{name}
 *  - DELETE /instance/delete/{name}
 *  - POST /message/sendText/{name}  (envia texto simples)
 */
@Slf4j
@Component
public class EvolutionClient {

    private final RestClient restClient;
    private final EvolutionProperties props;

    public EvolutionClient(EvolutionProperties props) {
        this.props = props;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(props.getTimeoutMs()).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(props.getTimeoutMs()).toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Cria instância na Evolution e já configura o webhook apontando pro nosso backend.
     * Idempotente: se a instância já existe com o mesmo nome, a Evolution devolve erro
     * tratável (caller decide reaproveitar a existente via consultarStatus).
     *
     * @return body bruto da Evolution — contém instance.instanceId, hash.apikey (token), etc.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> criarInstancia(String instanceName, String webhookUrl) {
        // Body mutável porque a chave "proxy" só entra se estiver configurada
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("instanceName", instanceName);
        body.put("qrcode", true);
        body.put("integration", "WHATSAPP-BAILEYS");
        body.put("webhook", Map.of(
                // enabled=true é OBRIGATÓRIO na v2.3.x: sem essa flag, a Evolution
                // simplesmente não persiste o webhook (silencioso, sem erro), e
                // mensagens recebidas nunca chegam no backend.
                "enabled", true,
                "url", webhookUrl,
                "byEvents", false,
                // base64=true é OBRIGATÓRIO: sem isso o QRCODE_UPDATED chega só
                // com o texto do QR, sem a imagem base64 que o frontend precisa.
                "base64", true,
                "events", java.util.List.of(
                        "MESSAGES_UPSERT",
                        "CONNECTION_UPDATE",
                        "QRCODE_UPDATED"
                )
        ));

        // Proxy residencial (Proxy-Seller) — só inclui se configurado. Evita que
        // o Baileys conecte direto pelo IP da VPS (datacenter), que é banido
        // rapidamente pelo WhatsApp.
        if (props.getProxy().isAtivo()) {
            body.put("proxy", Map.of(
                    "host", props.getProxy().getHost(),
                    "port", props.getProxy().getPort(),
                    "protocol", props.getProxy().getProtocol(),
                    "username", props.getProxy().getUsername(),
                    "password", props.getProxy().getPassword()
            ));
            log.info("[Evolution] criando instância {} com proxy {}:{}",
                    instanceName, props.getProxy().getHost(), props.getProxy().getPort());
        } else {
            log.warn("[Evolution] criando instância {} SEM proxy (risco de ban pelo WhatsApp)", instanceName);
        }

        return executar("POST", "/instance/create", apiKeyGlobal(), body, Map.class);
    }

    /**
     * Busca o QR Code atual da instância. Se já estiver conectada, Evolution
     * devolve um body sem qrcode/code (caller deve ter verificado status antes).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> conectar(String instanceName) {
        return executar("GET", "/instance/connect/" + instanceName, apiKeyGlobal(), null, Map.class);
    }

    /** Consulta estado: { instance: { state: "open" | "close" | "connecting" } } */
    @SuppressWarnings("unchecked")
    public Map<String, Object> consultarStatus(String instanceName) {
        return executar("GET", "/instance/connectionState/" + instanceName, apiKeyGlobal(), null, Map.class);
    }

    /** Faz logout (mantém a instância criada — pode reconectar com novo QR). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> logout(String instanceName) {
        return executar("DELETE", "/instance/logout/" + instanceName, apiKeyGlobal(), null, Map.class);
    }

    /**
     * Reinicia a sessão WebSocket da instância SEM precisar de novo QR.
     * Útil pra evitar shadow-ban silencioso do WhatsApp — refresca a sessão
     * Baileys mantendo o pareamento ativo.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> restart(String instanceName) {
        return executar("POST", "/instance/restart/" + instanceName, apiKeyGlobal(), null, Map.class);
    }

    /** Apaga a instância da Evolution (uso administrativo, raramente chamado). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deletar(String instanceName) {
        return executar("DELETE", "/instance/delete/" + instanceName, apiKeyGlobal(), null, Map.class);
    }

    /**
     * Lista TODAS as instâncias na Evolution. Usado pelo reset emergencial
     * pra encontrar instâncias órfãs (criadas por bugs antigos como o de
     * threads paralelas) com prefix {@code mydelivery-rest-{id}-...} e
     * deletá-las em lote.
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> fetchInstances() {
        try {
            Object resp = executar("GET", "/instance/fetchInstances", apiKeyGlobal(), null, Object.class);
            if (resp instanceof java.util.List<?> l) {
                java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
                }
                return out;
            }
            return java.util.Collections.emptyList();
        } catch (RuntimeException e) {
            log.warn("[Evolution] fetchInstances falhou: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Envia mensagem de texto. Number aceita formatos "55119...", "55119...@s.whatsapp.net"
     * (Evolution normaliza). Usa o token da instância (não a chave global).
     *
     * @param delayMs ms de "digitando…" mostrado ao destinatário antes da msg aparecer.
     *                Evolution v2 dispara presença composing automaticamente quando delay > 0.
     *                Passe 0 pra enviar imediatamente.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> enviarTexto(String instanceName, String instanceToken, String number, String text, int delayMs) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("number", number);
        body.put("text", text);
        if (delayMs > 0) body.put("delay", delayMs);
        String key = instanceToken != null && !instanceToken.isBlank() ? instanceToken : props.getApiKey();
        return executar("POST", "/message/sendText/" + instanceName, key, body, Map.class);
    }

    /** Overload sem delay — mantém compatibilidade. */
    public Map<String, Object> enviarTexto(String instanceName, String instanceToken, String number, String text) {
        return enviarTexto(instanceName, instanceToken, number, text, 0);
    }

    /**
     * Envia uma imagem (URL pública) com legenda. Usado pelo bot pra mandar a
     * logo do restaurante junto da resposta de cardápio (em vez de só o link,
     * que gerava preview vazio "quadrado preto" no WhatsApp).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> enviarMidia(String instanceName, String instanceToken, String number,
                                            String mediaUrl, String caption, int delayMs) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("number", number);
        body.put("mediatype", "image");
        body.put("media", mediaUrl);
        if (caption != null && !caption.isEmpty()) body.put("caption", caption);
        if (delayMs > 0) body.put("delay", delayMs);
        String key = instanceToken != null && !instanceToken.isBlank() ? instanceToken : props.getApiKey();
        return executar("POST", "/message/sendMedia/" + instanceName, key, body, Map.class);
    }

    /**
     * Marca uma mensagem específica como lida (anti-bot: humano não lê tudo
     * instantâneo). Chama Evolution endpoint /chat/markMessageAsRead/.
     *
     * Tolerante a falha: se Evolution não suportar ou retornar erro, ignora.
     * Marcar como lido é "nice to have" — não pode bloquear nem retentar.
     */
    @SuppressWarnings("unchecked")
    public void marcarComoLida(String instanceName, String instanceToken,
                               String remoteJid, String messageId, boolean fromMe) {
        try {
            java.util.Map<String, Object> msg = new java.util.HashMap<>();
            msg.put("remoteJid", remoteJid);
            msg.put("fromMe", fromMe);
            msg.put("id", messageId);
            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("read_messages", java.util.List.of(msg));
            String key = instanceToken != null && !instanceToken.isBlank() ? instanceToken : props.getApiKey();
            executar("POST", "/chat/markMessageAsRead/" + instanceName, key, body, Map.class);
        } catch (Exception ignored) {
            // best-effort — versões antigas da Evolution não têm esse endpoint
        }
    }

    /**
     * Devolve a configuração atual de webhook salva na Evolution pra essa instância.
     * Útil pra diagnosticar quando msgs não chegam no backend.
     * Resposta típica: { "enabled": true, "url": "https://.../api/webhooks/whatsapp/...", "events": [...] }
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> consultarWebhook(String instanceName) {
        return executar("GET", "/webhook/find/" + instanceName, apiKeyGlobal(), null, Map.class);
    }

    /**
     * Reseta o webhook da instância pra apontar pra {@code webhookUrl}.
     * Usado quando a URL salva na Evolution está errada (ex: env trocada após criação).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> definirWebhook(String instanceName, String webhookUrl) {
        Map<String, Object> body = Map.of(
                "webhook", Map.of(
                        "enabled", true,
                        "url", webhookUrl,
                        "byEvents", false,
                        "base64", true,
                        "events", java.util.List.of(
                                "MESSAGES_UPSERT",
                                "CONNECTION_UPDATE",
                                "QRCODE_UPDATED"
                        )
                )
        );
        return executar("POST", "/webhook/set/" + instanceName, apiKeyGlobal(), body, Map.class);
    }

    // ── infra ──

    private String apiKeyGlobal() {
        return props.getApiKey();
    }

    @SuppressWarnings("unchecked")
    private <T> T executar(String method, String path, String apiKey, Object body, Class<T> tipo) {
        try {
            Consumer<HttpHeaders> headers = h -> h.add("apikey", apiKey);
            return (T) switch (method) {
                case "POST"   -> restClient.post().uri(path).headers(headers).body(body == null ? "" : body).retrieve().body(tipo);
                case "GET"    -> restClient.get().uri(path).headers(headers).retrieve().body(tipo);
                case "DELETE" -> restClient.delete().uri(path).headers(headers).retrieve().body(tipo);
                default -> throw new IllegalArgumentException("Método não suportado: " + method);
            };
        } catch (RestClientResponseException e) {
            log.error("[Evolution] {} {} falhou [{}]: {}", method, path, e.getStatusCode(),
                    truncar(e.getResponseBodyAsString()));
            throw new RuntimeException("Evolution API: " + extrairMensagem(e));
        } catch (Exception e) {
            log.error("[Evolution] {} {} erro: {}", method, path, e.getMessage());
            throw new RuntimeException("Não foi possível contactar a Evolution API");
        }
    }

    private String extrairMensagem(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) return "HTTP " + e.getStatusCode().value();
        return truncar(body);
    }

    private String truncar(String s) {
        if (s == null) return "";
        return s.length() > 250 ? s.substring(0, 250) + "..." : s;
    }

    public EvolutionProperties props() { return props; }
}
