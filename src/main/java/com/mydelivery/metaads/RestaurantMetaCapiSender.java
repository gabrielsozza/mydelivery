package com.mydelivery.metaads;

import com.mydelivery.repository.RestauranteRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class RestaurantMetaCapiSender {
    private final RestaurantMetaTrackingRepository configs;
    private final RestauranteRepository restaurants;
    private final MetaAdsCrypto crypto;
    private final RestClient http = createHttp();
    public RestaurantMetaCapiSender(RestaurantMetaTrackingRepository configs, RestauranteRepository restaurants, MetaAdsCrypto crypto) {
        this.configs = configs; this.restaurants = restaurants; this.crypto = crypto;
    }
    private static RestClient createHttp() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); factory.setReadTimeout(3000);
        return RestClient.builder().baseUrl("https://graph.facebook.com/v25.0").requestFactory(factory).build();
    }
    @Async
    public void send(Long restaurantId, String eventName, String eventId, Double value,
            String contentId, String phone, String fbp, String fbc, String ip, String userAgent) {
        var config = configs.findById(restaurantId).orElse(null);
        if (config == null || !config.isEnabled() || config.getTokenEncrypted() == null) return;
        try {
            Map<String, Object> user = new LinkedHashMap<>();
            if (phone != null && !phone.isBlank()) {
                String digits = phone.replaceAll("\\D", "");
                if (digits.length() >= 10 && digits.length() <= 13) {
                    if (!digits.startsWith("55")) digits = "55" + digits;
                    user.put("ph", List.of(sha256(digits)));
                }
            }
            if (validCookie(fbp)) user.put("fbp", fbp);
            if (validCookie(fbc)) user.put("fbc", fbc);
            if (ip != null && !ip.isBlank()) user.put("client_ip_address", ip);
            if (userAgent != null && !userAgent.isBlank()) user.put("client_user_agent", userAgent);
            if (user.isEmpty()) return;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("event_name", eventName);
            data.put("event_time", Instant.now().getEpochSecond());
            data.put("event_id", eventId);
            data.put("action_source", "website");
            data.put("event_source_url", "https://mydeliveryfood.com.br/" + restaurants.findById(restaurantId).orElseThrow().getSlug());
            data.put("user_data", user);
            Map<String, Object> custom = new LinkedHashMap<>();
            if (value != null && Double.isFinite(value) && value >= 0) { custom.put("value", value); custom.put("currency", "BRL"); }
            if (contentId != null && !contentId.isBlank()) {
                if ("Purchase".equals(eventName)) custom.put("order_id", contentId.replace("order_", ""));
                else { custom.put("content_ids", List.of(contentId)); custom.put("content_type", "product"); }
            }
            if (!custom.isEmpty()) data.put("custom_data", custom);
            http.post().uri("/" + config.getPixelId() + "/events")
                    .body(Map.of("data", List.of(data), "access_token", crypto.decrypt(config.getTokenEncrypted())))
                    .retrieve().toBodilessEntity();
        } catch (Exception ignored) {
            // A Meta nunca impede pedidos nem interrompe a experiência do cliente.
        }
    }

    private static boolean validCookie(String value) { return value != null && value.length() <= 200 && value.matches("[A-Za-z0-9._-]+"); }
    private static String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format("%02x", b));
        return result.toString();
    }
}
