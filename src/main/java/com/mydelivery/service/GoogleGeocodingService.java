package com.mydelivery.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/** Geocodificação server-side para o endereço da loja. */
@Slf4j
@Service
public class GoogleGeocodingService {
    private final String apiKey;
    private final RestClient google;
    private final com.github.benmanes.caffeine.cache.Cache<String, List<Localizacao>> cache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(2000)
                    .expireAfterWrite(Duration.ofDays(30)).build();

    public GoogleGeocodingService(@Value("${GOOGLE_MAPS_API_KEY:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(8).toMillis());
        this.google = RestClient.builder().baseUrl("https://geocode.googleapis.com")
                .requestFactory(factory).defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).build();
    }

    public boolean configurado() { return !apiKey.isBlank(); }

    public List<Localizacao> localizar(String endereco) {
        if (!configurado() || endereco == null || endereco.isBlank() || endereco.length() > 600) return List.of();
        String chave = endereco.trim().toLowerCase(java.util.Locale.ROOT);
        List<Localizacao> locais = cache.get(chave, ignored -> consultar(endereco.trim()));
        if (locais.isEmpty()) cache.invalidate(chave);
        return locais;
    }

    @SuppressWarnings("unchecked")
    private List<Localizacao> consultar(String endereco) {
        try {
            Map<String, Object> resposta = google.get().uri(uri -> uri.path("/v4/geocode/address")
                    .queryParam("address.addressLines", endereco).queryParam("address.regionCode", "BR")
                    .queryParam("languageCode", "pt-BR").build())
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", "results.location,results.addressComponents")
                    .retrieve().body(Map.class);
            if (resposta == null || !(resposta.get("results") instanceof List<?> resultados)) return List.of();
            return resultados.stream().filter(Map.class::isInstance)
                    .map(item -> lerLocalizacao((Map<String, Object>) item))
                    .filter(java.util.Objects::nonNull).limit(5).toList();
        } catch (RestClientException e) {
            log.warn("[GoogleGeocoding] consulta indisponível; usando fallback: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    static Localizacao lerLocalizacao(Map<String, Object> resultado) {
        try {
            if (!(resultado.get("location") instanceof Map<?, ?> ponto)) return null;
            double lat = numero(ponto.get("latitude")), lng = numero(ponto.get("longitude"));
            if (Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
            String rua = "", numero = "", cep = "", cidade = "", uf = "", bairro = "";
            if (resultado.get("addressComponents") instanceof List<?> componentes) {
                for (Object item : componentes) {
                    if (!(item instanceof Map<?, ?> c) || !(c.get("types") instanceof List<?> tipos)) continue;
                    String longo = texto(c.get("longText")), curto = texto(c.get("shortText"));
                    if (tipos.contains("route")) rua = longo;
                    if (tipos.contains("street_number")) numero = longo;
                    if (tipos.contains("postal_code")) cep = longo;
                    if (tipos.contains("locality")) cidade = longo;
                    if (cidade.isBlank() && tipos.contains("administrative_area_level_2")) cidade = longo;
                    if (tipos.contains("administrative_area_level_1")) uf = curto;
                    if (bairro.isBlank() && (tipos.contains("neighborhood") || tipos.contains("sublocality")
                            || tipos.contains("sublocality_level_1") || tipos.contains("sublocality_level_2"))) bairro = longo;
                }
            }
            if (rua.isBlank()) return null;
            return new Localizacao(lat, lng, rua, numero, bairro, cidade, uf, cep);
        } catch (IllegalArgumentException e) { return null; }
    }

    private static double numero(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException();
        return n.doubleValue();
    }
    private static String texto(Object value) { return value == null ? "" : value.toString().trim(); }

    public record Localizacao(double lat, double lng, String rua, String numero, String bairro,
                              String cidade, String uf, String cep) {}
}
