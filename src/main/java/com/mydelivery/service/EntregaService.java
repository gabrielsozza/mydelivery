package com.mydelivery.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.mydelivery.model.Restaurante;
import com.mydelivery.model.ZonaEntrega;
import com.mydelivery.repository.ZonaEntregaRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Cálculo de taxa de entrega por raio + geocodificação de endereços.
 *
 * <p><b>Modelo dual (jul/2026)</b>: {@link Restaurante#getModoTaxa()} decide
 * como cobrar. Em BAIRRO usa a tabela existente {@code taxas_bairro} —
 * lógica antiga em {@code PedidoService.buscarTaxaPorBairro}. Em RAIO
 * usa este serviço.
 *
 * <p><b>Geocoder</b>: proxy pro Nominatim (OSM). Free, sem chave. Cache
 * in-memory por endereço evita bater a mesma consulta 2× no mesmo boot.
 * Nominatim tem "fair use policy" — mandamos User-Agent identificando o
 * app e ratelimitamos suavemente (60ms entre chamadas).
 */
@Slf4j
@Service
public class EntregaService {

    private final ZonaEntregaRepository zonaRepo;
    private final RestClient nominatim;
    /** Cache in-memory endereço→[lat,lng]. Curto (não persiste em restart)
     *  mas suficiente pra atenuar retry de cliente no checkout. */
    private final java.util.concurrent.ConcurrentHashMap<String, double[]> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long ultimaChamadaNominatim = 0;

    public EntregaService(ZonaEntregaRepository zonaRepo) {
        this.zonaRepo = zonaRepo;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(8).toMillis());
        this.nominatim = RestClient.builder()
                .baseUrl("https://nominatim.openstreetmap.org")
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                // Nominatim exige User-Agent identificando o app. Sem isso
                // eles retornam 403.
                .defaultHeader(HttpHeaders.USER_AGENT, "MyDelivery/1.0 (contato@mydeliveryfood.com.br)")
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════
    // CRUD zonas
    // ═════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<ZonaEntrega> listarZonas(Long restauranteId) {
        return zonaRepo.findByRestauranteIdOrderByRaioKmAsc(restauranteId);
    }

    /**
     * Substitui todas as zonas do restaurante numa transação — mais simples
     * que sincronizar diffs. As zonas são poucas (2-5 típico) e mudam rara
     * mente (ao configurar). Reordena por raio crescente antes de gravar.
     */
    @Transactional
    public List<ZonaEntrega> substituirZonas(Restaurante restaurante, List<Map<String, Object>> zonas) {
        zonaRepo.deleteByRestauranteId(restaurante.getId());
        if (zonas == null || zonas.isEmpty()) return List.of();
        List<ZonaEntrega> novas = new ArrayList<>();
        // Ordena por raio antes de setar ordem — garante que zona menor
        // apareça primeiro na busca (regra "primeira zona cujo raio >= dist").
        zonas.sort((a, b) -> {
            BigDecimal ra = decOf(a.get("raioKm"));
            BigDecimal rb = decOf(b.get("raioKm"));
            if (ra == null && rb == null) return 0;
            if (ra == null) return 1;
            if (rb == null) return -1;
            return ra.compareTo(rb);
        });
        int ordem = 0;
        for (Map<String, Object> z : zonas) {
            BigDecimal raio = decOf(z.get("raioKm"));
            BigDecimal taxa = decOf(z.get("taxa"));
            if (raio == null || raio.signum() <= 0 || taxa == null || taxa.signum() < 0) continue;
            novas.add(zonaRepo.save(ZonaEntrega.builder()
                    .restaurante(restaurante)
                    .raioKm(raio)
                    .taxa(taxa)
                    .ordem(ordem++)
                    .build()));
        }
        return novas;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Cálculo de taxa por raio
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Retorna a taxa aplicável ao destino {@code (destLat, destLng)} pro
     * restaurante — ou {@code null} se destino está fora de todas as zonas
     * ou o restaurante não tem coordenadas configuradas.
     *
     * <p>Exige {@link Restaurante#getEnderecoLatitude()} e {@link Restaurante#getEnderecoLongitude()}
     * — caller deve garantir ou pular a chamada.
     */
    @Transactional(readOnly = true)
    public BigDecimal calcularTaxaPorRaio(Restaurante restaurante, double destLat, double destLng) {
        if (restaurante.getEnderecoLatitude() == null || restaurante.getEnderecoLongitude() == null) {
            return null;
        }
        double origLat = restaurante.getEnderecoLatitude().doubleValue();
        double origLng = restaurante.getEnderecoLongitude().doubleValue();
        double distKm = distanciaHaversineKm(origLat, origLng, destLat, destLng);
        List<ZonaEntrega> zonas = zonaRepo.findByRestauranteIdOrderByRaioKmAsc(restaurante.getId());
        for (ZonaEntrega z : zonas) {
            if (z.getRaioKm().doubleValue() >= distKm) return z.getTaxa();
        }
        return null; // fora de todas as zonas → não entregamos aqui
    }

    /**
     * Fórmula de Haversine — distância entre 2 pontos na superfície da
     * Terra em quilômetros. Raio médio 6371km. Erro < 0.5% pra distâncias
     * curtas (delivery = tipicamente < 15km), aceitável pro caso de uso.
     */
    private static double distanciaHaversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0; // raio da Terra em km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Geocoder (Nominatim proxy)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Geocodifica string livre pra {@code [lat, lng]}. Retorna {@code null}
     * se Nominatim não achou ou falhou.
     *
     * <p>Rate limit interno: espera 60ms se a última chamada foi há menos.
     * Mantém a gente dentro do fair use do Nominatim (~1-2 req/s).
     *
     * <p>Cache in-memory por endereço normalizado — dedup de retries do
     * cliente no checkout. Zerado a cada restart.
     */
    public double[] geocodificar(String endereco) {
        if (endereco == null || endereco.isBlank()) return null;
        String chave = endereco.trim().toLowerCase().replaceAll("\\s+", " ");
        double[] cache1 = cache.get(chave);
        if (cache1 != null) return cache1;

        // Rate limit suave
        long agora = System.currentTimeMillis();
        long deltaMs = agora - ultimaChamadaNominatim;
        if (deltaMs < 60) {
            try { Thread.sleep(60 - deltaMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        ultimaChamadaNominatim = System.currentTimeMillis();

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resp = nominatim.get()
                    .uri(uri -> uri.path("/search")
                            .queryParam("q", endereco)
                            .queryParam("format", "json")
                            .queryParam("limit", 1)
                            .queryParam("countrycodes", "br")
                            .build())
                    .retrieve()
                    .body(List.class);
            if (resp == null || resp.isEmpty()) {
                log.debug("[Geocoder] sem resultado pra '{}'", endereco);
                return null;
            }
            Object lat = resp.get(0).get("lat");
            Object lon = resp.get(0).get("lon");
            if (lat == null || lon == null) return null;
            double[] coord = new double[] {
                    Double.parseDouble(lat.toString()),
                    Double.parseDouble(lon.toString())
            };
            cache.put(chave, coord);
            return coord;
        } catch (RestClientException | NumberFormatException e) {
            log.warn("[Geocoder] falha em '{}': {}", endereco, e.getMessage());
            return null;
        }
    }

    private static BigDecimal decOf(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return null; }
    }
}
