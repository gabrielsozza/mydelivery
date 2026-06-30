package com.mydelivery.service.afiliados;

import com.mydelivery.model.Plano;
import com.mydelivery.model.Restaurante;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dispara webhooks pra o myafiliados-api quando algo acontece com restaurante
 * que tem afiliadoCodigo preenchido.
 *
 * Fail-safe: NUNCA propaga erro pro fluxo principal. Se myafiliados-api estiver
 * fora, só registra log.
 */
@Slf4j
@Service
public class AfiliadosWebhookService {

    private final RestClient client;
    private final String secret;
    private final boolean ativo;

    public AfiliadosWebhookService(
            @Value("${mydelivery.afiliados.base-url:}") String baseUrl,
            @Value("${mydelivery.afiliados.webhook-secret:}") String secret) {
        this.secret = secret;
        this.ativo = baseUrl != null && !baseUrl.isBlank()
                && secret != null && !secret.isBlank();
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(8).toMillis());
        this.client = RestClient.builder()
                .baseUrl(ativo ? baseUrl : "http://localhost:65535")
                .requestFactory(factory)
                .build();
    }

    @Async
    public void restauranteCriado(Restaurante r, String linkOrigem) {
        if (!ativo || r == null || r.getAfiliadoCodigo() == null || r.getAfiliadoCodigo().isBlank()) return;
        Map<String, Object> body = baseBody("RESTAURANTE_CRIADO", r);
        if (linkOrigem != null) body.put("linkOrigem", linkOrigem.toUpperCase());
        enviar(body);
    }

    @Async
    public void restauranteAssinou(Restaurante r, Plano plano, BigDecimal valorPago) {
        if (!ativo || r == null || r.getAfiliadoCodigo() == null || r.getAfiliadoCodigo().isBlank()) return;
        Map<String, Object> body = baseBody("ASSINOU", r);
        body.put("planoContratado", plano.name());
        body.put("valorPlano", valorPago);
        // Mensal equivalente — pra plano dividir comissão
        int meses = plano.getDuracaoMeses();
        if (meses > 0 && valorPago != null) {
            body.put("valorMensalEquivalente",
                    valorPago.divide(new BigDecimal(meses), 2, RoundingMode.HALF_UP));
        }
        enviar(body);
    }

    @Async
    public void restauranteCancelou(Restaurante r) {
        if (!ativo || r == null || r.getAfiliadoCodigo() == null || r.getAfiliadoCodigo().isBlank()) return;
        enviar(baseBody("CANCELOU", r));
    }

    @Async
    public void trialExpirou(Restaurante r) {
        if (!ativo || r == null || r.getAfiliadoCodigo() == null || r.getAfiliadoCodigo().isBlank()) return;
        enviar(baseBody("TRIAL_EXPIROU", r));
    }

    private Map<String, Object> baseBody(String tipo, Restaurante r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", tipo);
        m.put("codigoAfiliado", r.getAfiliadoCodigo());
        m.put("restauranteId", r.getId());
        m.put("restauranteNome", r.getNome());
        m.put("restauranteSlug", r.getSlug());
        if (r.getUsuario() != null) m.put("restauranteEmail", r.getUsuario().getEmail());
        return m;
    }

    private void enviar(Map<String, Object> body) {
        try {
            client.post()
                    .uri("/api/webhooks/mydelivery/evento")
                    .header("X-Webhook-Secret", secret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[Afiliados] webhook {} enviado pra restaurante={} afiliado={}",
                    body.get("tipo"), body.get("restauranteId"), body.get("codigoAfiliado"));
        } catch (Exception e) {
            log.warn("[Afiliados] webhook {} FALHOU: {}", body.get("tipo"), e.getMessage());
        }
    }
}
