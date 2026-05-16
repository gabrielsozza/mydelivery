package com.mydelivery.service.whatsapp;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.mydelivery.config.WhatsappCloudProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Cliente HTTP pra Meta WhatsApp Cloud API.
 *
 * Auth: Bearer token no header Authorization.
 * Base URL: https://graph.facebook.com/{apiVersion}
 *
 * Métodos principais:
 *  - enviarTexto: envia mensagem de texto pra um número (formato E.164 sem +)
 *  - ehConfigurado: indica se as credenciais estão preenchidas (Cloud API ativa)
 */
@Slf4j
@Service
public class WhatsappCloudClient {

    private final WhatsappCloudProperties props;
    private final RestClient restClient;

    public WhatsappCloudClient(WhatsappCloudProperties props) {
        this.props = props;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(props.getTimeoutMs()).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(props.getTimeoutMs()).toMillis());

        this.restClient = RestClient.builder()
                .baseUrl("https://graph.facebook.com/" + props.getApiVersion())
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** True se Cloud API está habilitada e credenciais preenchidas. */
    public boolean ehConfigurado() {
        return props.isEnabled()
                && props.getPhoneNumberId() != null && !props.getPhoneNumberId().isBlank()
                && props.getAccessToken() != null && !props.getAccessToken().isBlank();
    }

    /**
     * Envia mensagem de texto.
     *
     * @param numeroDestino formato E.164 SEM "+" (ex: "5511999998888")
     * @param texto         conteúdo da mensagem
     */
    @SuppressWarnings("unchecked")
    public void enviarTexto(String numeroDestino, String texto) {
        if (!ehConfigurado()) {
            log.warn("[WA-Cloud] não configurado, ignorando envio pra {}", numeroDestino);
            return;
        }

        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "recipient_type", "individual",
                "to", numeroDestino,
                "type", "text",
                "text", Map.of("preview_url", false, "body", texto)
        );

        try {
            Map<String, Object> resp = restClient.post()
                    .uri("/{phoneId}/messages", props.getPhoneNumberId())
                    .header("Authorization", "Bearer " + props.getAccessToken())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            log.info("[WA-Cloud] msg enviada pra {} (resp={})", numeroDestino,
                    resp == null ? "null" : resp.get("messages"));
        } catch (RestClientResponseException e) {
            log.error("[WA-Cloud] erro {} ao enviar pra {}: {}",
                    e.getStatusCode(), numeroDestino, e.getResponseBodyAsString());
            throw new RuntimeException("Falha ao enviar mensagem WhatsApp Cloud: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("[WA-Cloud] erro inesperado enviando pra {}: {}", numeroDestino, e.getMessage());
            throw new RuntimeException("Falha ao enviar mensagem WhatsApp Cloud", e);
        }
    }
}
