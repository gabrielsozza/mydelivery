package com.mydelivery.service.meta;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.mydelivery.config.MetaCapiProperties;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Envia eventos pra Meta Conversions API (server-side).
 *
 * Eventos suportados:
 *  - completeRegistration(email, phone, name) → quando cadastro finaliza
 *  - initiateCheckout(email, phone, value) → quando cria preferência MP
 *  - subscribe(email, phone, value, mpPaymentId) → quando webhook MP confirma pago
 *
 * Todas as PII são SHA256-hashed antes de enviar — exigência da Meta.
 * Async pra não bloquear o fluxo principal (cadastro/webhook).
 * Falhas são silenciadas — Meta cair NUNCA pode afetar o sistema.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaCapiService {

    private final MetaCapiProperties props;
    private RestClient http;

    @PostConstruct
    public void init() {
        this.http = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Cadastro completo → equivalente ao Pixel "CompleteRegistration".
     * Dispara quando AuthService.cadastrar() salva o restaurante.
     */
    @Async
    public void completeRegistration(String email, String telefone, String nome) {
        enviar("CompleteRegistration", email, telefone, nome, null, null, null);
    }

    /**
     * Início de checkout → equivalente ao Pixel "InitiateCheckout".
     * Dispara quando cria preferência de pagamento no MP (intenção real de assinar).
     */
    @Async
    public void initiateCheckout(String email, String telefone, String nome, Double valor) {
        enviar("InitiateCheckout", email, telefone, nome, valor, null, null);
    }

    /**
     * Assinatura confirmada → equivalente ao Pixel "Subscribe".
     * Dispara quando webhook MP avisa que pagamento foi aprovado.
     * É o GOLDEN EVENT — Meta otimiza Lookalike a partir daqui.
     */
    @Async
    public void subscribe(String email, String telefone, String nome, Double valor, Long mpPaymentId) {
        String eventId = mpPaymentId == null ? null : "subscribe_" + mpPaymentId;
        enviar("Subscribe", email, telefone, nome, valor, mpPaymentId == null ? null : mpPaymentId.toString(), eventId);
    }

    // ─────────────────────── interno ───────────────────────

    private void enviar(String eventName, String email, String telefone, String nome,
                        Double valor, String orderId, String eventIdCustom) {
        if (!props.isAtivo()) {
            log.debug("[MetaCAPI] desligado (ativo=false) — ignorando {}", eventName);
            return;
        }
        if (props.getPixelId() == null || props.getPixelId().isBlank()
                || props.getAccessToken() == null || props.getAccessToken().isBlank()) {
            log.debug("[MetaCAPI] credenciais ausentes — ignorando {}", eventName);
            return;
        }

        try {
            Map<String, Object> userData = new LinkedHashMap<>();
            if (email != null && !email.isBlank()) userData.put("em", List.of(sha256(email.trim().toLowerCase())));
            String phoneClean = normalizarTelefone(telefone);
            if (phoneClean != null) userData.put("ph", List.of(sha256(phoneClean)));
            String[] partes = partirNome(nome);
            if (partes[0] != null) userData.put("fn", List.of(sha256(partes[0])));
            if (partes[1] != null) userData.put("ln", List.of(sha256(partes[1])));
            // País sempre BR (negócio é Brasil-only). Hash sem aspas.
            userData.put("country", List.of(sha256("br")));

            Map<String, Object> evento = new LinkedHashMap<>();
            evento.put("event_name", eventName);
            evento.put("event_time", Instant.now().getEpochSecond());
            evento.put("event_id", eventIdCustom != null ? eventIdCustom : UUID.randomUUID().toString());
            evento.put("action_source", "website");
            evento.put("event_source_url", "https://mydeliveryfood.com.br");
            evento.put("user_data", userData);

            if (valor != null) {
                Map<String, Object> customData = new LinkedHashMap<>();
                customData.put("currency", "BRL");
                customData.put("value", valor);
                if (orderId != null) customData.put("order_id", orderId);
                evento.put("custom_data", customData);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", List.of(evento));
            body.put("access_token", props.getAccessToken());
            if (props.getTestEventCode() != null && !props.getTestEventCode().isBlank()) {
                body.put("test_event_code", props.getTestEventCode());
            }

            String url = "/" + props.getPixelId() + "/events";
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = http.post()
                    .uri(url)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            log.info("[MetaCAPI] {} enviado — resp={}", eventName, resp);
        } catch (Exception e) {
            // CRÍTICO: nunca propagar falha — cadastro/webhook NÃO PODEM
            // depender da Meta API. Log warn e segue.
            log.warn("[MetaCAPI] falha enviando {}: {}", eventName, e.getMessage());
        }
    }

    private static String sha256(String input) {
        if (input == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Remove tudo que não é dígito, garante código país 55. */
    private static String normalizarTelefone(String tel) {
        if (tel == null || tel.isBlank()) return null;
        String d = tel.replaceAll("\\D", "");
        if (d.isEmpty()) return null;
        if (!d.startsWith("55") && d.length() <= 11) d = "55" + d;
        return d;
    }

    /** Separa "João Silva" em ["joão", "silva"] — lowercased pra Meta. */
    private static String[] partirNome(String nome) {
        if (nome == null || nome.isBlank()) return new String[]{null, null};
        String n = nome.trim().toLowerCase();
        int sp = n.indexOf(' ');
        if (sp < 0) return new String[]{n, null};
        return new String[]{n.substring(0, sp), n.substring(sp + 1).trim()};
    }
}
