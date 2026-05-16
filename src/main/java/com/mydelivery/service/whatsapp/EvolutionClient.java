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
        Map<String, Object> body = Map.of(
                "instanceName", instanceName,
                "qrcode", true,
                "integration", "WHATSAPP-BAILEYS",
                // webhook configurado já na criação — evita chamada extra a /webhook/set
                "webhook", Map.of(
                        "url", webhookUrl,
                        "byEvents", false,
                        // base64=true é OBRIGATÓRIO na v2.1.x: sem isso o
                        // QRCODE_UPDATED chega só com o texto do QR, sem
                        // a imagem base64 que o frontend precisa renderizar.
                        "base64", true,
                        "events", java.util.List.of(
                                "MESSAGES_UPSERT",
                                "CONNECTION_UPDATE",
                                "QRCODE_UPDATED"
                        )
                )
        );
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

    /** Apaga a instância da Evolution (uso administrativo, raramente chamado). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deletar(String instanceName) {
        return executar("DELETE", "/instance/delete/" + instanceName, apiKeyGlobal(), null, Map.class);
    }

    /**
     * Envia mensagem de texto. Number aceita formatos "55119...", "55119...@s.whatsapp.net"
     * (Evolution normaliza). Usa o token da instância (não a chave global).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> enviarTexto(String instanceName, String instanceToken, String number, String text) {
        Map<String, Object> body = Map.of(
                "number", number,
                "text", text
        );
        String key = instanceToken != null && !instanceToken.isBlank() ? instanceToken : props.getApiKey();
        return executar("POST", "/message/sendText/" + instanceName, key, body, Map.class);
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
