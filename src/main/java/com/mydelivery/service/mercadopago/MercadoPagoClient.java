package com.mydelivery.service.mercadopago;

import java.time.Duration;
import java.util.function.Consumer;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.mydelivery.config.MercadoPagoProperties;
import com.mydelivery.dto.mercadopago.MpPaymentRequest;
import com.mydelivery.dto.mercadopago.MpPaymentResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * HTTP client pra Payments API do Mercado Pago.
 *
 * Não guarda credenciais — todo método exige o accessToken do tenant.
 * Isso garante isolamento multi-tenant: é impossível chamar o MP "esquecendo"
 * de informar de qual restaurante a cobrança é.
 *
 * Erros do MP são propagados via RestClientResponseException. O caller decide
 * o que fazer (normalmente: log + RuntimeException mapeada pelo GlobalExceptionHandler).
 */
@Slf4j
@Component
public class MercadoPagoClient {

    private final RestClient restClient;
    private final MercadoPagoProperties props;

    public MercadoPagoClient(MercadoPagoProperties props) {
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
     * POST /v1/payments — cria o pagamento (PIX ou cartão).
     *
     * @param accessToken    token OAuth do restaurante
     * @param idempotencyKey chave determinística (ex: "mydelivery-pedido-123-pix").
     *                       MP devolve o MESMO pagamento se reenviada em até 24h —
     *                       elimina duplicação por retry de rede ou clique duplo do usuário.
     * @param body           request preparado pelo serviço chamador
     */
    public MpPaymentResponse criarPagamento(String accessToken, String idempotencyKey, MpPaymentRequest body) {
        try {
            return restClient.post()
                    .uri("/v1/payments")
                    .headers(authAndIdempotency(accessToken, idempotencyKey))
                    .body(body)
                    .retrieve()
                    .body(MpPaymentResponse.class);
        } catch (RestClientResponseException e) {
            log.error("MP /v1/payments falhou [{}]: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Falha ao criar pagamento no Mercado Pago: " + extrairMensagem(e));
        }
    }

    /**
     * GET /v1/payments/{id} — consulta atual do MP.
     *
     * NUNCA confie no payload do webhook pra status: ele só diz que algo MUDOU.
     * O valor real precisa vir desta consulta autenticada.
     */
    public MpPaymentResponse consultar(String accessToken, Long mpPaymentId) {
        try {
            return restClient.get()
                    .uri("/v1/payments/{id}", mpPaymentId)
                    .headers(h -> h.setBearerAuth(accessToken))
                    .retrieve()
                    .body(MpPaymentResponse.class);
        } catch (RestClientResponseException e) {
            log.error("MP GET /v1/payments/{} falhou [{}]: {}", mpPaymentId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Falha ao consultar pagamento no Mercado Pago: " + extrairMensagem(e));
        }
    }

    private Consumer<HttpHeaders> authAndIdempotency(String accessToken, String idempotencyKey) {
        return h -> {
            h.setBearerAuth(accessToken);
            // X-Idempotency-Key: header oficial do MP. Mesma chave → mesmo pagamento (24h).
            h.add("X-Idempotency-Key", idempotencyKey);
        };
    }

    /** Extrai mensagem útil do JSON de erro do MP (que tem formato { message, error, status, cause: [...] }). */
    private String extrairMensagem(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) return "HTTP " + e.getStatusCode();
        // Não vamos parsear: log já tem o body completo, mensagem curta pro usuário basta.
        if (body.length() > 200) return body.substring(0, 200) + "...";
        return body;
    }

    public MercadoPagoProperties props() {
        return props;
    }

    /**
     * Faz uma chamada leve ao MP só pra confirmar que o accessToken é válido.
     * Usamos GET /users/me — endpoint barato, não cria nada, retorna 401 se o token é inválido.
     *
     * @return null se OK; caso contrário, mensagem de erro pra exibir ao usuário.
     */
    public String validarToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) return "Access Token vazio";
        try {
            restClient.get()
                    .uri("/users/me")
                    .headers(h -> h.setBearerAuth(accessToken))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) return "Token inválido ou expirado";
            return "Mercado Pago respondeu " + e.getStatusCode().value();
        } catch (Exception e) {
            return "Não foi possível conectar ao Mercado Pago: " + e.getMessage();
        }
    }
}
