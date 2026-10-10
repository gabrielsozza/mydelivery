package com.mydelivery.metaads;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mydelivery.model.Restaurante;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MetaAdsService {
    private final MetaAdsConnectionRepository connections;
    private final MetaAdsOauthStateRepository states;
    private final MetaAdsCampaignRepository campaigns;
    private final MetaAdsCrypto crypto;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final SecureRandom random = new SecureRandom();
    private final boolean enabled;
    private final String appId, configId, appSecret, redirectUri, frontendUri, graphVersion;

    public MetaAdsService(MetaAdsConnectionRepository connections, MetaAdsOauthStateRepository states,
            MetaAdsCampaignRepository campaigns, MetaAdsCrypto crypto, ObjectMapper json,
            @Value("${mydelivery.meta-ads.enabled:false}") boolean enabled,
            @Value("${mydelivery.meta-ads.app-id:}") String appId,
            @Value("${mydelivery.meta-ads.config-id:}") String configId,
            @Value("${mydelivery.meta-ads.app-secret:}") String appSecret,
            @Value("${mydelivery.meta-ads.redirect-uri:}") String redirectUri,
            @Value("${mydelivery.meta-ads.frontend-uri:}") String frontendUri,
            @Value("${mydelivery.meta-ads.graph-version:v25.0}") String graphVersion) {
        this.connections = connections; this.states = states; this.campaigns = campaigns;
        this.crypto = crypto; this.json = json; this.enabled = enabled;
        this.appId = appId; this.configId = configId; this.appSecret = appSecret; this.redirectUri = redirectUri;
        this.frontendUri = frontendUri; this.graphVersion = graphVersion;
    }

    public boolean available() {
        return enabled && crypto.configured() && !appId.isBlank() && !configId.isBlank() && !appSecret.isBlank()
                && redirectUri.startsWith("https://") && frontendUri.startsWith("https://");
    }

    private void requireAvailable() {
        if (!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Integração Meta ainda não está liberada");
    }

    public Map<String, Object> status(Long restaurantId) {
        var c = connections.findById(restaurantId).orElse(null);
        boolean connected = c != null && (c.getTokenExpiresAt() == null || c.getTokenExpiresAt().isAfter(Instant.now()));
        return Map.of("available", available(), "connected", connected,
                "accountId", c != null && c.getAdAccountId() != null ? c.getAdAccountId() : "",
                "pageId", c != null && c.getPageId() != null ? c.getPageId() : "");
    }

    public String authorizeUrl(Long restaurantId) {
        requireAvailable();
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var pending = new MetaAdsOauthState();
        pending.setStateHash(sha256(state)); pending.setRestauranteId(restaurantId);
        pending.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        states.save(pending);
        return "https://www.facebook.com/" + graphVersion + "/dialog/oauth?" + form(Map.of(
                "client_id", appId, "redirect_uri", redirectUri, "response_type", "code",
                "config_id", configId,
                "state", state));
    }

    public String callback(String state, String code, String error) {
        String destination = frontendUri + "?meta=";
        if (state == null || state.length() > 128) return destination + "invalid_state";
        var pending = states.findById(sha256(state)).orElse(null);
        if (pending == null) return destination + "invalid_state";
        states.delete(pending); // single use, including denied/expired flows
        if (pending.getExpiresAt().isBefore(Instant.now())) return destination + "expired";
        if (error != null || code == null || code.isBlank()) return destination + "cancelled";
        try {
            requireAvailable();
            JsonNode shortToken = oauth(Map.of("client_id", appId, "client_secret", appSecret,
                    "redirect_uri", redirectUri, "code", code));
            String token = shortToken.path("access_token").asText();
            if (token.isBlank()) return destination + "failed";
            JsonNode longToken = oauth(Map.of("grant_type", "fb_exchange_token", "client_id", appId,
                    "client_secret", appSecret, "fb_exchange_token", token));
            token = longToken.path("access_token").asText();
            if (token.isBlank()) return destination + "failed";
            long seconds = longToken.path("expires_in").asLong(0);
            var c = connections.findById(pending.getRestauranteId()).orElseGet(MetaAdsConnection::new);
            c.setRestauranteId(pending.getRestauranteId());
            c.setTokenEncrypted(crypto.encrypt(token));
            c.setTokenExpiresAt(seconds > 0 ? Instant.now().plusSeconds(seconds) : null);
            c.setAdAccountId(null);
            c.setPageId(null);
            c.setUpdatedAt(Instant.now());
            connections.save(c);
            return destination + "connected";
        } catch (Exception e) {
            return destination + "failed";
        }
    }

    public JsonNode assets(Long restaurantId) {
        String token = token(restaurantId);
        var result = json.createObjectNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) result).set("accounts",
                get("me/adaccounts", Map.of("fields", "account_id,name,currency,account_status", "limit", "100"), token).path("data"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) result).set("pages",
                get("me/accounts", Map.of("fields", "id,name", "limit", "100"), token).path("data"));
        return result;
    }

    public void selectAssets(Long restaurantId, String accountId, String pageId) {
        if (!accountId.matches("[0-9]{1,32}") || !pageId.matches("[0-9]{1,32}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione uma conta e uma página válidas");
        JsonNode available = assets(restaurantId);
        boolean accountAllowed = false, pageAllowed = false;
        for (JsonNode a : available.path("accounts"))
            if (accountId.equals(a.path("account_id").asText()) && a.path("account_status").asInt() == 1) accountAllowed = true;
        for (JsonNode p : available.path("pages")) if (pageId.equals(p.path("id").asText())) pageAllowed = true;
        if (!accountAllowed || !pageAllowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "A conta ou a página não está disponível para esta conexão");
        var c = connections.findById(restaurantId).orElseThrow();
        c.setAdAccountId(accountId); c.setPageId(pageId); c.setUpdatedAt(Instant.now());
        connections.save(c);
    }

    public List<MetaAdsCampaign> campaigns(Long restaurantId) {
        return campaigns.findByRestauranteIdOrderByCreatedAtDesc(restaurantId);
    }

    public record Draft(String name, String text, String imageUrl, BigDecimal dailyBudget,
                        int days, int radiusKm) {}

    public MetaAdsCampaign createDraft(Restaurante restaurant, Draft draft) {
        var c = connection(restaurant.getId());
        if (c.getAdAccountId() == null || c.getPageId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha a conta de anúncios e a página primeiro");
        validateDraft(restaurant, draft);
        String token = token(restaurant.getId());
        String accountPath = "act_" + c.getAdAccountId();
        String name = draft.name().trim();
        String campaignId = null;
        Instant endAt = Instant.now().plus(draft.days(), ChronoUnit.DAYS);
        try {
            campaignId = post(accountPath + "/campaigns", Map.of("name", name,
                    "objective", "OUTCOME_TRAFFIC", "special_ad_categories", "[]",
                    "status", "PAUSED", "buying_type", "AUCTION"), token).path("id").asText();
            var targeting = Map.of("age_min", 18, "age_max", 65,
                    "geo_locations", Map.of("custom_locations", List.of(Map.of(
                            "latitude", restaurant.getEnderecoLatitude(), "longitude", restaurant.getEnderecoLongitude(),
                            "radius", draft.radiusKm(), "distance_unit", "kilometer"))));
            String adsetId = post(accountPath + "/adsets", Map.of("name", name,
                    "campaign_id", campaignId, "daily_budget", Long.toString(draft.dailyBudget().movePointRight(2).longValueExact()),
                    "billing_event", "IMPRESSIONS", "optimization_goal", "LINK_CLICKS",
                    "bid_strategy", "LOWEST_COST_WITHOUT_CAP", "destination_type", "WEBSITE",
                    "end_time", endAt.toString(),
                    "targeting", json.writeValueAsString(targeting), "status", "PAUSED"), token).path("id").asText();
            String link = "https://mydeliveryfood.com.br/" + restaurant.getSlug();
            var story = Map.of("page_id", c.getPageId(), "link_data", Map.of(
                    "link", link, "message", draft.text().trim(), "picture", draft.imageUrl().trim(),
                    "call_to_action", Map.of("type", "SHOP_NOW")));
            String creativeId = post(accountPath + "/adcreatives", Map.of("name", name,
                    "object_story_spec", json.writeValueAsString(story)), token).path("id").asText();
            String adId = post(accountPath + "/ads", Map.of("name", name,
                    "adset_id", adsetId, "creative", json.writeValueAsString(Map.of("creative_id", creativeId)),
                    "status", "PAUSED"), token).path("id").asText();
            MetaAdsCampaign saved = new MetaAdsCampaign();
            saved.setRestauranteId(restaurant.getId()); saved.setCampaignId(campaignId);
            saved.setAdAccountId(c.getAdAccountId());
            saved.setAdsetId(adsetId); saved.setCreativeId(creativeId); saved.setAdId(adId);
            saved.setName(name); saved.setDailyBudgetCents(draft.dailyBudget().movePointRight(2).longValueExact());
            saved.setEndAt(endAt);
            return campaigns.save(saved);
        } catch (Exception e) {
            if (campaignId != null && !campaignId.isBlank()) {
                try { post(campaignId, Map.of("status", "ARCHIVED"), token); } catch (Exception ignored) {}
            }
            if (e instanceof ResponseStatusException rse) throw rse;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "A Meta não aceitou o rascunho; confira imagem, conta e permissões");
        }
    }

    static void validateDraft(Restaurante r, Draft d) {
        if (d == null || d.name() == null || d.name().isBlank() || d.name().length() > 120
                || d.text() == null || d.text().isBlank() || d.text().length() > 1000
                || d.dailyBudget() == null || d.dailyBudget().compareTo(BigDecimal.TEN) < 0
                || d.dailyBudget().compareTo(new BigDecimal("1000")) > 0
                || d.dailyBudget().scale() > 2 || d.days() < 1 || d.days() > 30
                || d.radiusKm() < 10 || d.radiusKm() > 40)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Revise texto, orçamento, duração e alcance");
        if (d.imageUrl() == null || !d.imageUrl().matches("https://[^\\s]{1,1500}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use uma imagem pública com endereço HTTPS");
        if (r.getEnderecoLatitude() == null || r.getEnderecoLongitude() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Defina a localização da loja em Configurações antes de anunciar");
    }

    public MetaAdsCampaign setStatus(Long restaurantId, Long id, boolean active) {
        MetaAdsCampaign c = campaigns.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!restaurantId.equals(c.getRestauranteId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!c.getAdAccountId().equals(connection(restaurantId).getAdAccountId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selecione a conta usada nesta campanha");
        if (active && !c.getEndAt().isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta campanha já terminou. Crie um novo anúncio.");
        post(c.getCampaignId(), Map.of("status", active ? "ACTIVE" : "PAUSED"), token(restaurantId));
        c.setStatus(active ? "ACTIVE" : "PAUSED");
        return campaigns.save(c);
    }

    public JsonNode insights(Long restaurantId, Long id) {
        MetaAdsCampaign c = campaigns.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!restaurantId.equals(c.getRestauranteId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!c.getAdAccountId().equals(connection(restaurantId).getAdAccountId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selecione a conta usada nesta campanha");
        return get(c.getCampaignId() + "/insights", Map.of("fields", "spend,impressions,clicks,inline_link_clicks",
                "date_preset", "last_30d"), token(restaurantId)).path("data");
    }

    public void disconnect(Long restaurantId) { connections.deleteById(restaurantId); }

    private MetaAdsConnection connection(Long restaurantId) {
        return connections.findById(restaurantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Conecte a Meta primeiro"));
    }

    private String token(Long restaurantId) {
        requireAvailable();
        var c = connection(restaurantId);
        if (c.getTokenExpiresAt() != null && c.getTokenExpiresAt().isBefore(Instant.now()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Conexão Meta expirada; conecte novamente");
        return crypto.decrypt(c.getTokenEncrypted());
    }

    private JsonNode oauth(Map<String, String> params) { return request("oauth/access_token", params, null, true); }
    private JsonNode get(String path, Map<String, String> params, String token) { return request(path, params, token, false); }
    private JsonNode post(String path, Map<String, String> params, String token) { return request(path, params, token, true); }

    private JsonNode request(String path, Map<String, String> params, String token, boolean post) {
        try {
            Map<String, String> values = new LinkedHashMap<>(params);
            if (token != null) values.put("appsecret_proof", appSecretProof(token));
            String base = "https://graph.facebook.com/" + graphVersion + "/" + path;
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (post) builder.uri(URI.create(base)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(values)));
            else builder.uri(URI.create(base + "?" + form(values))).GET();
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = json.readTree(response.body());
            if (response.statusCode() >= 400 || body.has("error"))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "A Meta recusou a operação. Confira o acesso à conta e tente novamente.");
            return body;
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Não foi possível comunicar com a Meta"); }
    }

    private String appSecretProof(String token) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
