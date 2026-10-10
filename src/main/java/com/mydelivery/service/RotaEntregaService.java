package com.mydelivery.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Percurso de ida pelas ruas. Nunca substitui falhas por distância em linha reta. */
@Service
public class RotaEntregaService {
    private final RestClient client;
    private final ReentrantLock limite = new ReentrantLock();
    private long ultimaConsulta;
    private final com.github.benmanes.caffeine.cache.Cache<String, Double> cache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(10000)
                    .expireAfterWrite(Duration.ofHours(6)).build();

    public RotaEntregaService(@Value("${entrega.routing-url:https://routing.openstreetmap.de/routed-car}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(4000);
        factory.setReadTimeout(6000);
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("User-Agent", "MyDelivery/1.0 (contato@mydeliveryfood.com.br)")
                .defaultHeader("Accept", "application/json").build();
    }

    public double distanciaKm(double origemLat, double origemLng, double destinoLat, double destinoLng) {
        EntregaService.validarCoordenadas(origemLat, origemLng);
        EntregaService.validarCoordenadas(destinoLat, destinoLng);
        // Não arredondar nem inverter origem/destino: mãos únicas alteram o percurso.
        String coords = origemLng + "," + origemLat + ";" + destinoLng + "," + destinoLat;
        return cache.get(coords, this::consultar);
    }

    public record Amostra(double lat,double lng,Double km) {}
    public record Cobertura(int lado, List<Amostra> pontos) {}
    private final com.github.benmanes.caffeine.cache.Cache<String,Cobertura> coberturaCache =
        com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofHours(24)).build();

    /** Uma matriz de rotas por origem/limite; a malha é apenas uma estimativa visual. */
    public Cobertura cobertura(double lat,double lng,double km) {
        EntregaService.validarCoordenadas(lat,lng);
        if (!Double.isFinite(km) || km<=0 || km>100) throw new IllegalArgumentException("A prévia permite limites de até 100 km.");
        return coberturaCache.get(lat+","+lng+"|"+km, key -> consultarCobertura(lat,lng,km));
    }
    private Cobertura consultarCobertura(double lat,double lng,double km) {
        int lado=11;
        var coords=new StringBuilder(lng+","+lat);
        var pontos=new java.util.ArrayList<Amostra>();
        var indices=new java.util.ArrayList<Integer>();
        double cos=Math.cos(Math.toRadians(lat));
        if (Math.abs(cos)<0.1) throw indisponivel();
        for(int y=0;y<lado;y++) for(int x=0;x<lado;x++) {
            double dy=(y-5)*km/5,dx=(x-5)*km/5;
            double a=lat+dy/111.195,b=lng+dx/(111.195*cos);
            pontos.add(new Amostra(a,b,null));
            if(dx*dx+dy*dy<=km*km+1e-8) { indices.add(pontos.size()-1);coords.append(";").append(b).append(",").append(a); }
        }
        try {
            aguardarLimite();
            Map<?,?> body=client.get().uri("/table/v1/driving/"+coords+"?sources=0&annotations=distance")
                .retrieve().body(Map.class);
            if(body==null || !"Ok".equals(body.get("code")) || !(body.get("distances") instanceof List<?> rows)
                || rows.isEmpty() || !(rows.getFirst() instanceof List<?> distances)
                || !(body.get("destinations") instanceof List<?> destinations) || distances.size()!=indices.size()+1)
                throw indisponivel();
            double origem=numero(((Map<?,?>)destinations.getFirst()).get("distance"));
            if(origem>75) throw indisponivel();
            for(int i=0;i<indices.size();i++) {
                if(distances.get(i+1)==null) continue;
                double acesso=numero(((Map<?,?>)destinations.get(i+1)).get("distance"));
                // Não desloca a malha para estradas muito distantes (rios/áreas sem acesso).
                if(acesso>Math.min(300,km*100)) continue;
                int idx=indices.get(i);var ponto=pontos.get(idx);
                pontos.set(idx,new Amostra(ponto.lat(),ponto.lng(),(numero(distances.get(i+1))+origem+acesso)/1000));
            }
            return new Cobertura(lado,List.copyOf(pontos));
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw indisponivel(); }
        catch(RuntimeException e) { throw indisponivel(); }
    }
    private void aguardarLimite() throws InterruptedException {
        if (!limite.tryLock(50, TimeUnit.MILLISECONDS)) throw indisponivel();
        try {
            long restante=TimeUnit.SECONDS.toNanos(1)-(System.nanoTime()-ultimaConsulta);
            if(restante>0) TimeUnit.NANOSECONDS.sleep(restante);
            ultimaConsulta=System.nanoTime();
        } finally { limite.unlock(); }
    }
    private double consultar(String coords) {
        try {
            aguardarLimite();
            Map<?, ?> body = client.get().uri("/route/v1/driving/" + coords
                    + "?overview=false&alternatives=false&steps=false&radiuses=75;75")
                    .retrieve().body(Map.class);
            return lerDistanciaKm(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw indisponivel();
        } catch (RuntimeException e) {
            // Não memoriza erros e não libera uma entrega sem percurso calculado.
            throw indisponivel();
        }
    }

    static double lerDistanciaKm(Map<?, ?> body) {
        if (body == null || !"Ok".equals(body.get("code"))
                || !(body.get("routes") instanceof List<?> routes) || routes.isEmpty()
                || !(routes.getFirst() instanceof Map<?, ?> route)
                || !(body.get("waypoints") instanceof List<?> points) || points.size() != 2)
            throw indisponivel();
        double metros = numero(route.get("distance"));
        for (Object value : points) {
            if (!(value instanceof Map<?, ?> point)) throw indisponivel();
            double acesso = numero(point.get("distance"));
            if (acesso > 75) throw indisponivel();
            // Inclui os acessos entre endereço e via para não subestimar o limite.
            metros += acesso;
        }
        return metros / 1000.0;
    }

    private static double numero(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0)
            throw indisponivel();
        return n.doubleValue();
    }
    private static IllegalArgumentException indisponivel() {
        return new IllegalArgumentException("Não foi possível confirmar o percurso da entrega. Confira o endereço e tente novamente.");
    }
}
