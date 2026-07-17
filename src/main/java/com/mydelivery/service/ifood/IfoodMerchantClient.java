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
        this.orderClient = orderClient;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) java.time.Duration.ofMillis(props.getTimeoutMs()).toMillis());
        factory.setReadTimeout((int) java.time.Duration.ofMillis(props.getTimeoutMs()).toMillis());
        this.restClient = RestClient.builder()
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
            throw new RuntimeException("iFood: " + e.getStatusCode() + " " + e.getResponseBodyAsString());
        }
    }

    /**
     * Detalhes da loja. Retorna {@code {id, name, corporateName, description,
     * averageTicket, minimumOrderValue, phones[], address{...}, ...}}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> detalhesMerchant(String merchantId) {
        try {
            return restClient.get()
                    .uri("/merchant/v1.0/merchants/{id}", merchantId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + orderClient.obterAccessToken())
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            log.error("[iFood-Merchant] Detalhe falhou [{}]: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("iFood: " + e.getStatusCode());
        }
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
            throw new RuntimeException("iFood: " + e.getStatusCode());
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
            throw new RuntimeException("iFood: " + e.getStatusCode());
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
            throw new RuntimeException("iFood: " + e.getStatusCode() + " — " + e.getResponseBodyAsString());
        }
    }

    /** Remove uma pausa pelo id retornado no create ou no list. */
    public void removerInterrupcao(String merchantId, String interruptionId) {
        try {
            restClient.delete()
                    .uri("/merchant/v1.0/merchants/{merchantId}/interruptions/{id}",
                            merchantId, interruptionId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + orderClient.obterAccessToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("[iFood-Merchant] Remover pausa falhou [{}]: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("iFood: " + e.getStatusCode());
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
            throw new RuntimeException("iFood: " + e.getStatusCode());
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
            throw new RuntimeException("iFood: " + e.getStatusCode() + " — " + e.getResponseBodyAsString());
        }
    }
}
