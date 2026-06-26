package com.mydelivery.service.ifood;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.mydelivery.config.IfoodProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Cliente HTTP da Order API do iFood (merchant-api.ifood.com.br).
 *
 * Responsabilidades:
 *  - Obter e cachear access_token (OAuth client_credentials)
 *  - Polling de eventos (/events:polling) + ack
 *  - Detalhe de pedido (/orders/{id})
 *  - Atualizar status (confirm, dispatch, cancel etc.)
 *
 * Padrão usado: mesmo do EvolutionClient — RestClient nativo, sem Feign,
 * com timeout configurado e mensagens de erro tratáveis.
 *
 * Thread-safe: o cache de token usa AtomicReference. Múltiplas threads
 * (polling job + endpoints manuais) podem chamar simultâneamente.
 */
@Slf4j
@Component
public class IfoodClient {

    private final IfoodProperties props;
    private final RestClient restClient;

    /** Cache do access_token. expira após o expiresInSec da resposta OAuth. */
    private final AtomicReference<TokenCache> tokenCache = new AtomicReference<>();

    public IfoodClient(IfoodProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(props.getTimeoutMs()).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(props.getTimeoutMs()).toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    // ── OAuth ────────────────────────────────────────────────────────────

    private static class TokenCache {
        final String accessToken;
        final Instant expiraEm;
        TokenCache(String t, long ttlSec) {
            this.accessToken = t;
            // expira 60s antes do declarado pra evitar usar token vencido por race
            this.expiraEm = Instant.now().plusSeconds(Math.max(60, ttlSec - 60));
        }
        boolean valido() { return Instant.now().isBefore(expiraEm); }
    }

    /** Retorna access_token válido. Renova se expirou ou nunca foi obtido. */
    public synchronized String obterAccessToken() {
        TokenCache atual = tokenCache.get();
        if (atual != null && atual.valido()) return atual.accessToken;

        log.info("[iFood] Renovando access_token (client_credentials)");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grantType", "client_credentials");
        form.add("clientId", props.getClientId());
        form.add("clientSecret", props.getClientSecret());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restClient.post()
                    .uri("/authentication/v1.0/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            if (resp == null) throw new RuntimeException("Resposta OAuth vazia");
            String token = (String) resp.get("accessToken");
            Number ttl = (Number) resp.get("expiresIn");
            if (token == null) throw new RuntimeException("Sem accessToken no body");
            long ttlSec = ttl == null ? 3600L : ttl.longValue();
            tokenCache.set(new TokenCache(token, ttlSec));
            log.info("[iFood] access_token obtido (expira em {}s)", ttlSec);
            return token;
        } catch (RestClientResponseException e) {
            log.error("[iFood] Falha OAuth [{}]: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("iFood OAuth falhou: " + e.getStatusCode());
        } catch (Exception e) {
            log.error("[iFood] Erro inesperado no OAuth: {}", e.getMessage());
            throw new RuntimeException("iFood OAuth indisponível");
        }
    }

    // ── Events Polling ───────────────────────────────────────────────────

    /**
     * Polling de eventos. Retorna lista de eventos pendentes (orderId + tipo).
     * Quando processados, devem ser ack-ados via {@link #acknowledgeEventos}.
     *
     * Tipos comuns de evento iFood:
     *  - PLC (placed)          → pedido novo criado pelo cliente
     *  - CFM (confirmed)       → restaurante confirmou
     *  - CAN (cancelled)       → cancelado (pelo cliente ou loja)
     *  - DSP (dispatched)      → saiu pra entrega
     *  - CON (concluded)       → entregue
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> pollingEventos() {
        try {
            List<Map<String, Object>> eventos = restClient.get()
                    .uri("/order/v1.0/events:polling")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + obterAccessToken())
                    .retrieve()
                    .body(List.class);
            return eventos == null ? List.of() : eventos;
        } catch (RestClientResponseException e) {
            log.warn("[iFood] Polling falhou [{}]: {}", e.getStatusCode(),
                    truncar(e.getResponseBodyAsString()));
            return List.of();
        } catch (Exception e) {
            log.warn("[iFood] Polling: rede indisponível ({})", e.getMessage());
            return List.of();
        }
    }

    /**
     * Confirma o recebimento dos eventos pro iFood não reentregar.
     * Body: [{ "id": "evento-id-1" }, ...] — só os IDs dos eventos.
     */
    public void acknowledgeEventos(List<String> eventoIds) {
        if (eventoIds == null || eventoIds.isEmpty()) return;
        try {
            List<Map<String, String>> body = eventoIds.stream()
                    .map(id -> Map.of("id", id))
                    .toList();
            restClient.post()
                    .uri("/order/v1.0/events/acknowledgment")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + obterAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("[iFood] ACK falhou (ok — proximo poll retenta): {}", e.getMessage());
        }
    }

    // ── Detalhe de pedido ────────────────────────────────────────────────

    /**
     * Busca os detalhes completos de um pedido a partir do orderId.
     * Body retornado pelo iFood é o "Virtual Order" — JSON grande com
     * cliente, itens, totais, endereço, modo de entrega.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getOrderDetalhe(String orderId) {
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/order/v1.0/orders/" + orderId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + obterAccessToken())
                    .retrieve()
                    .body(Map.class);
            return body == null ? Map.of() : body;
        } catch (RestClientResponseException e) {
            log.error("[iFood] getOrderDetalhe falhou pra {} [{}]: {}",
                    orderId, e.getStatusCode(), truncar(e.getResponseBodyAsString()));
            throw new RuntimeException("iFood orderDetail falhou: " + e.getStatusCode());
        }
    }

    // ── Atualização de status ───────────────────────────────────────────

    /** Confirma o pedido (após o restaurante aceitar). */
    public void confirmar(String orderId) { postStatus(orderId, "confirm", Map.of()); }

    /** Marca como em preparo. */
    public void emPreparo(String orderId) { postStatus(orderId, "startPreparation", Map.of()); }

    /** Marca pronto pra retirada (próprio entregador) ou em rota. */
    public void pronto(String orderId) { postStatus(orderId, "readyToPickup", Map.of()); }

    /** Marca como despachado (saiu pra entrega — entrega própria). */
    public void despachado(String orderId) { postStatus(orderId, "dispatch", Map.of()); }

    /**
     * Marca como entregue (CONCLUDED). Usado quando a entrega é própria do
     * restaurante (logistic=MERCHANT) E o iFood não gera CON automático.
     *
     * Pra logística iFood/Loggi, o CON é gerado automaticamente pelo iFood
     * via polling — nesse caso essa chamada falha com 400/409 (que ignoramos
     * pra não bloquear a transição local). É idempotente.
     */
    public void entregue(String orderId) {
        try { postStatus(orderId, "delivered", Map.of()); }
        catch (RuntimeException e) {
            // iFood pode rejeitar (pedido já estava concluded, logística não
            // permite, etc). Não é fatal — só registra.
            log.warn("[iFood] delivered rejeitado pra {} (provavelmente já concluído ou logística iFood): {}",
                    orderId, e.getMessage());
        }
    }

    /** Cancela o pedido. Motivo precisa ser código válido do iFood. */
    public void cancelar(String orderId, String motivoCodigo, String motivoTexto) {
        postStatus(orderId, "requestCancellation", Map.of(
                "reason", motivoTexto == null ? "" : motivoTexto,
                "cancellationCode", motivoCodigo == null ? "501" : motivoCodigo
        ));
    }

    /**
     * Aceita uma solicitação de cancelamento iniciada pelo CLIENTE no app
     * iFood (evento CCR = CANCELLATION_REQUESTED). Quando o cliente cancela
     * via app, o iFood NÃO cancela direto — ele pergunta pro restaurante
     * se aceita ou não. Sem responder em ~10 min, o iFood penaliza.
     *
     * Como nosso modelo é auto-aceitar tudo (pedidos iFood vêm pagos), também
     * auto-aceitamos cancelamentos do cliente.
     *
     * Idempotente: chamar 2x não dá erro fatal (engole 4xx).
     */
    public void aceitarCancelamento(String orderId) {
        try { postStatus(orderId, "acceptCancellation", Map.of()); }
        catch (RuntimeException e) {
            log.warn("[iFood] acceptCancellation rejeitado pra {} (provavelmente já processado): {}",
                    orderId, e.getMessage());
        }
    }

    private void postStatus(String orderId, String acao, Map<String, Object> body) {
        try {
            restClient.post()
                    .uri("/order/v1.0/orders/" + orderId + "/" + acao)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + obterAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[iFood] {} → orderId={}", acao, orderId);
        } catch (RestClientResponseException e) {
            log.error("[iFood] {} falhou pra {} [{}]: {}",
                    acao, orderId, e.getStatusCode(), truncar(e.getResponseBodyAsString()));
            throw new RuntimeException("iFood " + acao + " falhou: " + e.getStatusCode());
        }
    }

    private static String truncar(String s) {
        if (s == null) return "";
        return s.length() > 250 ? s.substring(0, 250) + "..." : s;
    }

    /** Healthcheck simples — apenas tenta obter token. Usado em /diag. */
    public boolean ping() {
        try { return obterAccessToken() != null; }
        catch (Exception e) { return false; }
    }
}
