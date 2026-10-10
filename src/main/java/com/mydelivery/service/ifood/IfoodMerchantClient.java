package com.mydelivery.service.ifood;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import com.mydelivery.config.IfoodProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Cliente da <b>Merchant API</b> do iFood.
 *
 * <p>Módulo obrigatório pra homologação de apps Food PDV. Cobre:
 * <ul>
 *   <li>Informações da loja (list, detail, status)</li>
 *   <li>Interruptions (pausas) — CRUD</li>
 *   <li>Opening hours (horários) — get/put</li>
 * </ul>
 *
 * <p>Reutiliza o {@link IfoodClient#obterAccessToken()} pra compartilhar
 * o cache de token OAuth. Cada instância desta classe cria seu próprio
 * RestClient com timeout — assim latência da Merchant API não impacta
 * o polling de Order.
 *
 * <p>Docs: https://developer.ifood.com.br/pt-BR/docs/references/merchant
 */
@Slf4j
@Component
public class IfoodMerchantClient {

    private final IfoodClient orderClient;
    private final RestClient restClient;

    @Autowired
    public IfoodMerchantClient(IfoodClient orderClient, IfoodProperties props) {
        this(orderClient, criarRestClient(props));
    }

    IfoodMerchantClient(IfoodClient orderClient, RestClient restClient) {
        this.orderClient = orderClient;
        this.restClient = restClient;
    }

    private static RestClient criarRestClient(IfoodProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) java.time.Duration.ofMillis(props.getTimeoutMs()).toMillis());
        factory.setReadTimeout((int) java.time.Duration.ofMillis(props.getTimeoutMs()).toMillis());
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("User-Agent", "MyDelivery-Merchant/1.0 (+https://mydeliveryfood.com.br)")
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════
    // CENÁRIO 1: Informações da Loja
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Lista todos os merchants vinculados à conta do Developer Portal.
     * Retorna array bruto — cada item tem {@code id, name, corporateName}.
     * Homologação exige mostrar essa lista pro dono.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listarMerchants() {
        try {
            return restClient.get()
                    .uri("/merchant/v1.0/merchants")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + orderClient.obterAccessToken())
                    .retrieve()
                    .body(List.class);
        } catch (RestClientResponseException e) {
            log.error("[iFood-Merchant] Falha ao listar merchants [{}]: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new IfoodApiException(e.getStatusCode().value(),
                    e.getResponseBodyAsString(), "GET /merchant/v1.0/merchants");
        }
    }

    /**
     * Detalhes da loja. Retorna {@code {id, name, corporateName, description,
     * averageTicket, minimumOrderValue, phones[], address{...}, ...}}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> detalhesMerchant(String merchantId) {
        String token = orderClient.obterAccessToken();
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            try {
                return restClient.get()
                        .uri("/merchant/v1.0/merchants/{id}", merchantId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .retrieve()
                        .body(Map.class);
            } catch (RestClientResponseException e) {
                if (tentativa == 0 && (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403)) {
                    orderClient.invalidarAccessToken(token);
                    token = orderClient.obterAccessToken();
                    continue;
                }
                log.error("[iFood-Merchant] Detalhe falhou [{}]: {}",
                        e.getStatusCode(), e.getResponseBodyAsString());
                throw new IfoodApiException(e.getStatusCode().value(),
                        e.getResponseBodyAsString(), "GET /merchants/" + merchantId);
            }
        }
        throw new IllegalStateException("Consulta da loja não concluída");
    }

    /**
     * Disponibilidade da loja no iFood. Retorna array de "status" com
     * {@code available: true/false, state, reopenable}. O elemento crítico
     * pra homologação é o {@code available} — indica se cliente consegue
     * fazer pedido AGORA.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> statusMerchant(String merchantId) {
        try {
            return restClient.get()
                    .uri("/merchant/v1.0/merchants/{id}/status", merchantId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + orderClient.obterAccessToken())
                    .retrieve()
                    .body(List.class);
        } catch (RestClientResponseException e) {
            log.error("[iFood-Merchant] Status falhou [{}]: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new IfoodApiException(e.getStatusCode().value(),
                    e.getResponseBodyAsString(), "GET /merchants/" + merchantId + "/status");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CENÁRIO 2: Interrupções (pausas)
    // ═══════════════════════════════════════════════════════════════════

    /** Lista pausas ATIVAS e agendadas. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listarInterrupcoes(String merchantId) {
        try {
            return restClient.get()
                    .uri("/merchant/v1.0/merchants/{id}/interruptions", merchantId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + orderClient.obterAccessToken())
                    .retrieve()
                    .body(List.class);
        } catch (RestClientResponseException e) {
            log.error("[iFood-Merchant] Listar pausas falhou [{}]: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new IfoodApiException(e.getStatusCode().value(),
                    e.getResponseBodyAsString(), "GET /merchants/" + merchantId + "/interruptions");
        }
    }

    /**
     * Cria uma pausa. Formato esperado pelo iFood:
     * <pre>
     * { "description": "Fechado pra manutenção",
     *   "start": "2026-07-16T18:00:00.000-03:00",
     *   "end":   "2026-07-16T20:00:00.000-03:00" }
     * </pre>
     * Datas devem incluir timezone offset.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> criarInterrupcao(String merchantId, String descricao,
                                                 String inicioIso, String fimIso) {
        Map<String, Object> body = Map.of(
                "description", descricao == null ? "Pausa temporária" : descricao,
                "start", inicioIso,
                "end", fimIso
        );
        try {
            return restClient.post()
                    .uri("/merchant/v1.0/merchants/{id}/interruptions", merchantId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + orderClient.obterAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            log.error("[iFood-Merchant] Criar pausa falhou [{}]: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new IfoodApiException(e.getStatusCode().value(),
                    e.getResponseBodyAsString(), "POST interruptions");
        }
    }

    /** Remove uma pausa pelo id retornado no create ou no list. */
    public void removerInterrupcao(String merchantId, String interruptionId) {
        // A exclusão pode ser aplicada mesmo se a conexão encerrar sem resposta.
        // Consulte a fonte antes de repetir ou informar falha ao restaurante.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                restClient.delete()
                        .uri("/merchant/v1.0/merchants/{merchantId}/interruptions/{id}",
                                merchantId, interruptionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + orderClient.obterAccessToken())
                        .header(HttpHeaders.CONTENT_LENGTH, "0")
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (ResourceAccessException e) {
                log.warn("[iFood-Merchant] Resposta da remoção indisponível para pausa {}; conferindo lista (tentativa {})",
                        interruptionId, attempt + 1);
                if (confirmarAusencia(merchantId, interruptionId)) return;
                if (attempt == 0) continue;
                throw new IfoodApiException(504,
                        "O iFood não confirmou a remoção. Atualize a lista de pausas e tente novamente se ela continuar aparecendo.",
                        "DELETE interruption " + interruptionId);
            } catch (RestClientResponseException e) {
                boolean reconciliar = e.getStatusCode().value() == 404 || e.getStatusCode().is5xxServerError();
                if (reconciliar && confirmarAusencia(merchantId, interruptionId)) return;
                if (e.getStatusCode().is5xxServerError() && attempt == 0) continue;
                log.error("[iFood-Merchant] Remover pausa falhou [{}]: {}",
                        e.getStatusCode(), e.getResponseBodyAsString());
                throw new IfoodApiException(e.getStatusCode().value(),
                        e.getResponseBodyAsString(), "DELETE interruption " + interruptionId);
            }
        }
    }

    private boolean confirmarAusencia(String merchantId, String interruptionId) {
        try {
            var pausas = listarInterrupcoes(merchantId);
            boolean ausente = pausas != null && pausas.stream()
                    .noneMatch(pausa -> interruptionId.equals(String.valueOf(pausa.get("id"))));
            if (ausente) log.info("[iFood-Merchant] Remoção da pausa {} confirmada pela listagem", interruptionId);
            return ausente;
        } catch (ResourceAccessException | IfoodApiException e) {
            log.warn("[iFood-Merchant] Não foi possível confirmar a remoção da pausa {}", interruptionId);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CENÁRIO 3: Opening hours (horários)
    // ═══════════════════════════════════════════════════════════════════

    /** Retorna horários configurados. Formato:
     *  {@code { shifts: [ { dayOfWeek, start, duration } ] }} */
    @SuppressWarnings("unchecked")
    public Map<String, Object> horariosMerchant(String merchantId) {
        try {
            return restClient.get()
                    .uri("/merchant/v1.0/merchants/{id}/opening-hours", merchantId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + orderClient.obterAccessToken())
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            log.error("[iFood-Merchant] Horários falhou [{}]: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new IfoodApiException(e.getStatusCode().value(),
                    e.getResponseBodyAsString(), "GET opening-hours");
        }
    }

    /**
     * Sobrescreve os horários da loja. shifts é lista de:
     * {@code { dayOfWeek: "SATURDAY", start: "10:00:00", duration: 540 }}
     * onde duration está em MINUTOS. Um dia com múltiplas janelas manda
     * múltiplos shifts pro mesmo dayOfWeek.
     */
    public void atualizarHorarios(String merchantId, List<Map<String, Object>> shifts) {
        Map<String, Object> body = Map.of("shifts", shifts);
        try {
            restClient.put()
                    .uri("/merchant/v1.0/merchants/{id}/opening-hours", merchantId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + orderClient.obterAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("[iFood-Merchant] Atualizar horários falhou [{}]: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new IfoodApiException(e.getStatusCode().value(),
                    e.getResponseBodyAsString(), "PUT opening-hours");
        }
    }
}
