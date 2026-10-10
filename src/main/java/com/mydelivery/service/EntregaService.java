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
 * app e limitamos a uma chamada por segundo por instância.
 */
@Slf4j
@Service
public class EntregaService {

    private final ZonaEntregaRepository zonaRepo;
    private final RestClient nominatim;
    private final RestClient photon;
    @org.springframework.beans.factory.annotation.Autowired
    private RotaEntregaService rotaEntregaService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GoogleGeocodingService googleGeocodingService;
    public static boolean porDistancia(Restaurante r) {
        return "RAIO".equalsIgnoreCase(r.getModoTaxa()) || "TRAJETO".equalsIgnoreCase(r.getModoTaxa());
    }
    /** Cache in-memory endereço→[lat,lng]. Curto (não persiste em restart)
     *  mas suficiente pra atenuar retry de cliente no checkout. */
    private final com.github.benmanes.caffeine.cache.Cache<String, List<Localizacao>> cache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(5000)
                    .expireAfterWrite(Duration.ofHours(24)).build();
    private volatile long ultimaChamadaNominatim = 0;
    private final java.util.concurrent.locks.ReentrantLock limiteGeocoder = new java.util.concurrent.locks.ReentrantLock();

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
        this.photon = RestClient.builder().baseUrl("https://photon.komoot.io")
                .requestFactory(factory).defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "MyDelivery/1.0 (contato@mydeliveryfood.com.br)").build();
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
        if (zonas == null) throw new IllegalArgumentException("Informe as zonas de entrega.");
        if (porDistancia(restaurante) && zonas.isEmpty())
            throw new IllegalArgumentException("Cadastre ao menos uma zona de entrega com distância e taxa.");
        List<ZonaEntrega> novas = new ArrayList<>();
        var raios = new java.util.HashSet<BigDecimal>();
        for (Map<String, Object> z : zonas) {
            BigDecimal raio = z == null ? null : decOf(z.get("raioKm"));
            BigDecimal taxa = z == null ? null : decOf(z.get("taxa"));
            if (raio == null || raio.signum() <= 0 || raio.compareTo(new BigDecimal("9999.99")) > 0
                    || raio.stripTrailingZeros().scale() > 2 || taxa == null || taxa.signum() < 0
                    || taxa.compareTo(new BigDecimal("99999999.99")) > 0 || taxa.stripTrailingZeros().scale() > 2)
                throw new IllegalArgumentException("Preencha o raio e a taxa de todas as zonas, com até duas casas decimais.");
            if (!raios.add(raio.stripTrailingZeros()))
                throw new IllegalArgumentException("Cada zona deve ter um raio diferente.");
            novas.add(ZonaEntrega.builder().restaurante(restaurante).raioKm(raio).taxa(taxa).build());
        }
        novas.sort(java.util.Comparator.comparing(ZonaEntrega::getRaioKm));
        // Valida tudo antes de excluir: um erro nunca apaga a configuração anterior.
        zonaRepo.deleteByRestauranteId(restaurante.getId());
        for (int i = 0; i < novas.size(); i++) {
            novas.get(i).setOrdem(i);
            zonaRepo.save(novas.get(i));
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
        validarCoordenadas(origLat, origLng);
        validarCoordenadas(destLat, destLng);
        double distKm;
        if ("TRAJETO".equalsIgnoreCase(restaurante.getModoTaxa())) {
            if (rotaEntregaService == null) throw new IllegalArgumentException("Não foi possível consultar o percurso. Tente novamente.");
            distKm = rotaEntregaService.distanciaKm(origLat, origLng, destLat, destLng);
        } else {
            distKm = distanciaHaversineKm(origLat, origLng, destLat, destLng);
        }
        List<ZonaEntrega> zonas = zonaRepo.findByRestauranteIdOrderByRaioKmAsc(restaurante.getId());
        if (zonas.isEmpty()) throw new IllegalArgumentException("A loja ainda não configurou as distâncias e taxas de entrega. Fale com a loja.");
        for (ZonaEntrega z : zonas) {
            if (z.getRaioKm().doubleValue() + 1e-9 >= distKm) return z.getTaxa();
        }
        return null; // fora de todas as zonas → não entregamos aqui
    }

    /**
     * Fórmula de Haversine — distância entre 2 pontos na superfície da
     * Terra em quilômetros. Raio médio 6371km. Erro < 0.5% pra distâncias
     * curtas (delivery = tipicamente < 15km), aceitável pro caso de uso.
     */
    public static double distanciaHaversineKm(double lat1, double lon1, double lat2, double lon2) {
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

    public record Localizacao(double lat, double lng, String precisao, List<String> bairros,
                               String cidade, String uf, String numero, double incertezaKm, String rua, String cep) {
        public Localizacao(double lat, double lng, String precisao, List<String> bairros,
                String cidade, String uf, String numero, double incertezaKm) {
            this(lat,lng,precisao,bairros,cidade,uf,numero,incertezaKm,"","");
        }
    }

    /** Não permite que uma rua homônima substitua o endereço cadastrado. */
    public static boolean correspondeEndereco(Localizacao local, String rua, String numero, String bairro, String cidade, String uf, String cep) {
        if (!normalizarEndereco(local.rua()).equals(normalizarEndereco(rua))
                || !normalizarEndereco(local.cidade()).equals(normalizarEndereco(cidade))
                || !(local.uf().equalsIgnoreCase(uf) || local.uf().equalsIgnoreCase("BR-"+uf))) return false;
        var bairros = local.bairros().stream().map(b -> new com.mydelivery.model.BairroEntrega(normalizarEndereco(b),null)).toList();
        if (com.mydelivery.util.BairroNormalizer.encontrar(bairros,normalizarEndereco(bairro)) == null) return false;
        String postal = cep.replaceAll("\\D", "");
        if (!local.cep().isBlank() && !postal.isEmpty() && !local.cep().replaceAll("\\D", "").equals(postal)) return false;
        return !"imovel".equals(local.precisao()) || local.numero().equalsIgnoreCase(numero.trim());
    }
    private static String normalizarEndereco(String value) {
        return com.mydelivery.util.BairroNormalizer.normalizar(value)
                .replaceAll("[’']", "").replaceFirst("^r\\s+", "rua ");
    }

    /** Busca aproximada EXCLUSIVA para o dono conferir a origem. Nunca usada para
     * precificar entregas nem tratada como localização exata de um número. */
    public List<Localizacao> localizarRuaParaConferencia(String rua, String bairro, String cidade, String uf) {
        String query = rua.trim()+", "+bairro.trim()+", "+cidade.trim();
        String chave = "rua-conferencia|"+uf+"|"+query;
        var resultado = cache.get(chave, k -> {
            try {
                if (!throttleNominatim()) return List.of();
                Map<?,?> resposta = photon.get().uri(u -> u.path("/api/").queryParam("q",query)
                        .queryParam("limit",5).build()).retrieve().body(Map.class);
                if (resposta == null || !(resposta.get("features") instanceof List<?> features)) return List.of();
                return features.stream().filter(Map.class::isInstance).map(f -> lerRuaPhoton((Map<?,?>)f,rua,bairro,cidade,uf))
                        .filter(java.util.Objects::nonNull).toList();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (RestClientException e) {
                log.warn("[Geocoder] busca de rua indisponível: {}",e.getClass().getSimpleName());
                return List.of();
            }
        });
        if (resultado.isEmpty()) cache.invalidate(chave);
        return resultado;
    }

    static Localizacao lerRuaPhoton(Map<?,?> feature, String rua, String bairro, String cidade, String uf) {
        try {
            if (!(feature.get("properties") instanceof Map<?,?> p) || !(feature.get("geometry") instanceof Map<?,?> g)
                    || !(g.get("coordinates") instanceof List<?> coords) || coords.size()!=2
                    || !"BR".equals(p.get("countrycode")) || !"street".equals(p.get("type"))
                    || !"highway".equals(p.get("osm_key"))) return null;
            String nome = String.valueOf(p.get("name"));
            String municipio = String.valueOf(p.get("city"));
            String estado = String.valueOf(p.get("state"));
            String[] siglas = "AC AL AP AM BA CE DF ES GO MA MT MS MG PA PB PR PE PI RJ RN RS RO RR SC SP SE TO".split(" ");
            String[] estados = "Acre|Alagoas|Amapá|Amazonas|Bahia|Ceará|Distrito Federal|Espírito Santo|Goiás|Maranhão|Mato Grosso|Mato Grosso do Sul|Minas Gerais|Pará|Paraíba|Paraná|Pernambuco|Piauí|Rio de Janeiro|Rio Grande do Norte|Rio Grande do Sul|Rondônia|Roraima|Santa Catarina|São Paulo|Sergipe|Tocantins".split("\\|");
            int indice = java.util.Arrays.asList(siglas).indexOf(uf.toUpperCase(java.util.Locale.ROOT));
            if (indice<0 || !normalizarEndereco(estado).equals(normalizarEndereco(estados[indice]))
                    || !normalizarEndereco(municipio).equals(normalizarEndereco(cidade)) || !mesmaRuaAproximada(nome,rua)) return null;
            var bairros = new ArrayList<String>();
            for (String campo : List.of("district","locality")) if(p.get(campo)!=null) bairros.add(p.get(campo).toString());
            // Sem bairro exato não se aceita uma rua parecida. CEP do mapa pode estar
            // desatualizado; este retorno é sempre rua pendente de confirmação humana.
            if (bairros.stream().noneMatch(b -> normalizarEndereco(b).equals(normalizarEndereco(bairro)))) return null;
            double lng = Double.parseDouble(coords.get(0).toString()), lat = Double.parseDouble(coords.get(1).toString());
            validarCoordenadas(lat,lng);
            return new Localizacao(lat,lng,"rua",bairros,municipio,uf,"",Double.POSITIVE_INFINITY,nome,
                    p.get("postcode")==null ? "" : p.get("postcode").toString());
        } catch (IllegalArgumentException e) { return null; }
    }

    static boolean mesmaRuaAproximada(String a, String b) {
        String[] x = normalizarEndereco(a).split(" "), y = normalizarEndereco(b).split(" ");
        if(x.length != y.length) return false;
        int diferencas = 0;
        for(int i=0;i<x.length;i++) {
            if(x[i].equals(y[i])) continue;
            // Só uma troca de letra em palavra longa. Não confunde Rua 1/2,
            // Rio Jucu/Rua Jucu, nomes curtos ou omissão de sobrenomes.
            if(x[i].length()<5 || x[i].length()!=y[i].length()) return false;
            for(int j=0;j<x[i].length();j++) if(x[i].charAt(j)!=y[i].charAt(j)) diferencas++;
            if(diferencas>1) return false;
        }
        return true;
    }

    public record CotacaoBairro(String bairro, BigDecimal taxa, Double lat, Double lng) {}

    /** O bairro determina o preço final. Em lojas por distância, as faixas
     * usam o ponto de referência do bairro, tanto na consulta quanto no pedido. */
    @Transactional(readOnly = true)
    public CotacaoBairro consultarTaxaBairro(Restaurante r, String bairro) {
        if (bairro == null || bairro.isBlank() || bairro.length()>120)
            throw new IllegalArgumentException("Informe o bairro de entrega.");
        var cadastrado = com.mydelivery.util.BairroNormalizer.encontrar(r.getBairrosAtendidos(),bairro);
        String nome = cadastrado == null ? bairro.trim() : cadastrado.getNome();
        if (!porDistancia(r)) return new CotacaoBairro(nome,cadastrado==null ? null : cadastrado.getTaxa(),null,null);
        if (r.getEnderecoLatitude()==null || r.getEnderecoLongitude()==null)
            throw new IllegalArgumentException("A loja precisa configurar a área de entrega.");
        var locais = localizarBairro(nome,r.getCidade(),r.getEstado());
        if (locais.isEmpty()) return new CotacaoBairro(nome,null,null,null);
        var local = locais.getFirst();
        return new CotacaoBairro(local.bairros().getFirst(),calcularTaxaPorRaio(r,local.lat(),local.lng()),local.lat(),local.lng());
    }

    /** A cotação do percurso e a criação do pedido usam o mesmo endereço, sem confiar em coordenadas do cliente. */
    @Transactional(readOnly = true)
    public CotacaoBairro consultarTaxaEndereco(Restaurante r, String rua, String numero, String bairro, String cidade, String uf, String cep) {
        if (!"TRAJETO".equalsIgnoreCase(r.getModoTaxa())) return consultarTaxaBairro(r,bairro);
        if (rua==null || rua.isBlank() || rua.length()>200 || numero==null || numero.isBlank() || numero.length()>120
                || bairro==null || bairro.isBlank() || bairro.length()>120)
            throw new IllegalArgumentException("Preencha rua, número e bairro para consultar o percurso.");
        String cid=cidade==null || cidade.isBlank()?r.getCidade():cidade.trim();
        String estado=uf==null || uf.isBlank()?r.getEstado():uf.trim();
        String postal=cep==null?"":cep.trim();
        if (cid==null || cid.isBlank() || cid.length()>120 || estado==null || estado.length()!=2 || postal.length()>10)
            throw new IllegalArgumentException("Informe cidade e estado válidos.");
        if (r.getEnderecoLatitude()==null || r.getEnderecoLongitude()==null)
            throw new IllegalArgumentException("A loja precisa configurar sua localização.");
        String consulta=rua.trim()+", "+numero.trim()+", "+bairro.trim()+", "+cid+", "+estado+(postal.isBlank()?"":", "+postal);
        var locais=localizar(consulta,r.getEnderecoLatitude().doubleValue(),r.getEnderecoLongitude().doubleValue()).stream()
                .filter(l -> "imovel".equals(l.precisao()) && correspondeEndereco(l,rua,numero,bairro,cid,estado,postal)).toList();
        if (locais.isEmpty()) throw new IllegalArgumentException("Não localizamos esse número com segurança. Confira rua, número, bairro e cidade ou fale com a loja.");
        var local=locais.getFirst();
        if (locais.stream().anyMatch(l -> distanciaHaversineKm(local.lat(),local.lng(),l.lat(),l.lng())>0.1))
            throw new IllegalArgumentException("Endereço ambíguo. Confira o endereço completo.");
        return new CotacaoBairro(bairro.trim(),calcularTaxaPorRaio(r,local.lat(),local.lng()),local.lat(),local.lng());
    }

    List<Localizacao> localizarBairro(String bairro,String cidade,String uf) {
        if(cidade==null || cidade.isBlank() || uf==null || uf.isBlank())
            throw new IllegalArgumentException("A loja precisa configurar cidade e estado.");
        String chave="bairro-final|"+uf+"|"+cidade+"|"+normalizarEndereco(bairro);
        var locais=cache.get(chave,k -> {
            try {
                if(!throttleNominatim()) throw new IllegalArgumentException("Tente consultar o bairro novamente.");
                Map<?,?> resposta=photon.get().uri(u -> u.path("/api/")
                        .queryParam("q",bairro+", "+cidade+", "+nomeEstado(uf))
                        .queryParam("layer","district").queryParam("limit",8).build()).retrieve().body(Map.class);
                if(resposta==null || !(resposta.get("features") instanceof List<?> features)) return List.of();
                var candidatos=features.stream().filter(Map.class::isInstance)
                        .map(f -> lerBairroPhoton((Map<?,?>)f,cidade,uf)).filter(java.util.Objects::nonNull).toList();
                var selecionados=selecionarBairro(candidatos,bairro);
                return selecionados.isEmpty() ? consultarBairroNominatim(bairro,cidade,uf) : selecionados;
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalArgumentException("Não foi possível consultar a taxa agora. Tente novamente.");
            } catch(RestClientException e) {
                throw new IllegalArgumentException("Não foi possível consultar a taxa agora. Tente novamente.");
            }
        });
        if(locais.isEmpty()) cache.invalidate(chave);
        else cache.put("bairro-final|"+uf+"|"+cidade+"|"+normalizarEndereco(locais.getFirst().bairros().getFirst()),locais);
        return locais;
    }

    private List<Localizacao> consultarBairroNominatim(String bairro,String cidade,String uf) throws InterruptedException {
        if(!throttleNominatim()) throw new IllegalArgumentException("Tente consultar o bairro novamente.");
        List<?> resposta=nominatim.get().uri(u -> u.path("/search").queryParam("q",bairro+", "+cidade+", "+uf)
                .queryParam("format","jsonv2").queryParam("addressdetails",1).queryParam("countrycodes","br")
                .queryParam("limit",5).build()).retrieve().body(List.class);
        if(resposta==null) return List.of();
        var candidatos=resposta.stream().filter(Map.class::isInstance)
                .map(m -> lerBairroNominatim((Map<?,?>)m,cidade,uf)).filter(java.util.Objects::nonNull).toList();
        return selecionarBairro(candidatos,bairro);
    }

    static Localizacao lerBairroNominatim(Map<?,?> item,String cidade,String uf) {
        try {
            if(!(item.get("address") instanceof Map<?,?> a)
                    || !List.of("suburb","neighbourhood","quarter","borough","city_district","hamlet","village","locality").contains(String.valueOf(item.get("addresstype")))
                    || !normalizarEndereco(String.valueOf(a.get("city")!=null?a.get("city"):a.get("town")!=null?a.get("town"):a.get("municipality"))).equals(normalizarEndereco(cidade))
                    || !("BR-"+uf).equalsIgnoreCase(String.valueOf(a.get("ISO3166-2-lvl4")))
                    || item.get("name")==null || item.get("name").toString().isBlank()) return null;
            double lat=Double.parseDouble(item.get("lat").toString()),lng=Double.parseDouble(item.get("lon").toString());
            validarCoordenadas(lat,lng);
            return new Localizacao(lat,lng,"bairro",List.of(item.get("name").toString()),cidade,uf,"",Double.POSITIVE_INFINITY,"","");
        } catch(IllegalArgumentException e) { return null; }
    }

    static List<Localizacao> selecionarBairro(List<Localizacao> candidatos,String bairro) {
        var nomes=candidatos.stream().map(l -> new com.mydelivery.model.BairroEntrega(l.bairros().getFirst(),null)).toList();
        var reconhecido=com.mydelivery.util.BairroNormalizer.encontrar(nomes,bairro);
        if(reconhecido==null) return List.of();
        var compativeis=candidatos.stream().filter(l -> normalizarEndereco(l.bairros().getFirst()).equals(normalizarEndereco(reconhecido.getNome()))).toList();
        var primeiro=compativeis.getFirst();
        if(compativeis.stream().anyMatch(l -> distanciaHaversineKm(l.lat(),l.lng(),primeiro.lat(),primeiro.lng())>0.1)) return List.of();
        return List.of(primeiro);
    }

    static Localizacao lerBairroPhoton(Map<?,?> feature,String cidade,String uf) {
        try {
            if(!(feature.get("properties") instanceof Map<?,?> p) || !(feature.get("geometry") instanceof Map<?,?> g)
                    || !(g.get("coordinates") instanceof List<?> coords) || coords.size()!=2
                    || !"BR".equals(p.get("countrycode")) || !"district".equals(p.get("type"))
                    || !List.of("suburb","neighbourhood","quarter","borough","city_district","administrative","hamlet","village","locality").contains(String.valueOf(p.get("osm_value")))
                    || !normalizarEndereco(String.valueOf(p.get("city"))).equals(normalizarEndereco(cidade))
                    || !normalizarEndereco(String.valueOf(p.get("state"))).equals(normalizarEndereco(nomeEstado(uf)))
                    || p.get("name")==null || p.get("name").toString().isBlank()) return null;
            double lng=Double.parseDouble(coords.get(0).toString()),lat=Double.parseDouble(coords.get(1).toString());
            validarCoordenadas(lat,lng);
            return new Localizacao(lat,lng,"bairro",List.of(p.get("name").toString()),cidade,uf,"",Double.POSITIVE_INFINITY,"","");
        } catch(IllegalArgumentException e) { return null; }
    }

    private static String nomeEstado(String uf) {
        String[] siglas="AC AL AP AM BA CE DF ES GO MA MT MS MG PA PB PR PE PI RJ RN RS RO RR SC SP SE TO".split(" ");
        String[] estados="Acre|Alagoas|Amapá|Amazonas|Bahia|Ceará|Distrito Federal|Espírito Santo|Goiás|Maranhão|Mato Grosso|Mato Grosso do Sul|Minas Gerais|Pará|Paraíba|Paraná|Pernambuco|Piauí|Rio de Janeiro|Rio Grande do Norte|Rio Grande do Sul|Rondônia|Roraima|Santa Catarina|São Paulo|Sergipe|Tocantins".split("\\|");
        int i=java.util.Arrays.asList(siglas).indexOf(uf.toUpperCase(java.util.Locale.ROOT));
        return i<0 ? uf : estados[i];
    }

    /** Uma rua sem número localizado só recebe taxa automática se toda sua extensão
     * possível custa o mesmo. Evita cobrar pela metade da rua que cai em outra faixa. */
    @Transactional(readOnly = true)
    public boolean taxaUnicaNaRua(Restaurante r, Localizacao local) {
        // Um intervalo em linha reta não limita percursos com desvios ou mãos únicas.
        // Para trajetos, exige-se localizar o imóvel; nunca precificar pelo centro da rua.
        if ("TRAJETO".equalsIgnoreCase(r.getModoTaxa())) return false;
        if (!Double.isFinite(local.incertezaKm()) || r.getEnderecoLatitude() == null || r.getEnderecoLongitude() == null) return false;
        double dist = distanciaHaversineKm(r.getEnderecoLatitude().doubleValue(), r.getEnderecoLongitude().doubleValue(), local.lat(), local.lng());
        double min = Math.max(0, dist-local.incertezaKm()), max = dist+local.incertezaKm();
        var zonas = listarZonas(r.getId());
        BigDecimal taxa = taxaNaDistancia(zonas, min);
        if (!mesmaTaxa(taxa, taxaNaDistancia(zonas, max))) return false;
        for (var z : zonas) {
            double limite = z.getRaioKm().doubleValue();
            if (limite >= min && limite < max && !mesmaTaxa(taxa, taxaNaDistancia(zonas, limite+1e-7))) return false;
        }
        return true;
    }
    private BigDecimal taxaNaDistancia(List<ZonaEntrega> zonas, double km) {
        for (var z: zonas) if (z.getRaioKm().doubleValue()+1e-9 >= km) return z.getTaxa();
        return null;
    }
    private boolean mesmaTaxa(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    public static void validarCoordenadas(double lat, double lng) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180)
            throw new IllegalArgumentException("Localização inválida. Confira o ponto no mapa.");
    }

    public double[] geocodificar(String endereco) { return geocodificar(endereco, null, null); }
    public double[] geocodificar(String endereco, Double lat, Double lng) {
        var encontrados = localizar(endereco, lat, lng);
        return encontrados.isEmpty() ? null : new double[]{encontrados.getFirst().lat(), encontrados.getFirst().lng()};
    }

    /**
     * O botão de configuração da loja prioriza o Google, quando a chave estiver
     * configurada, e mantém o provedor atual como fallback. Chamadas públicas do
     * checkout continuam no fluxo gratuito e não consomem a cota do Google.
     */
    public List<Localizacao> localizarEnderecoLoja(String endereco) {
        if (googleGeocodingService != null && googleGeocodingService.configurado()) {
            var locais = googleGeocodingService.localizar(endereco);
            if (!locais.isEmpty()) return locais.stream().map(l -> new Localizacao(l.lat(),l.lng(),
                    l.precisao(),l.bairros(),
                    l.cidade(),l.uf(),l.numero(),"imovel".equals(l.precisao())?0:Double.POSITIVE_INFINITY,l.rua(),l.cep())).toList();
        }
        return localizar(endereco, null, null);
    }

    /** Só retorna ruas ou imóveis. Centros de cidade, CEP e bairro não são endereços. */
    public List<Localizacao> localizar(String endereco, Double lat, Double lng) {
        if (endereco == null || endereco.isBlank() || endereco.length() > 600) return List.of();
        if (lat != null && lng != null) validarCoordenadas(lat, lng);
        String chave = lat + "," + lng + "|" + endereco.trim().toLowerCase(java.util.Locale.ROOT);
        var locais = cache.get(chave, k -> consultarNominatim(endereco, lat, lng));
        if (locais.isEmpty()) cache.invalidate(chave); // Falhas temporárias não ficam memorizadas por 24h.
        return locais;
    }

    // Uma chamada por segundo para toda a instância, incluindo consultas concorrentes.
    private boolean throttleNominatim() throws InterruptedException {
        // Não forma uma fila ilimitada de checkouts aguardando o provedor público.
        if (!limiteGeocoder.tryLock(50, java.util.concurrent.TimeUnit.MILLISECONDS)) return false;
        try {
            long restante = 1000 - (System.currentTimeMillis() - ultimaChamadaNominatim);
            if (restante > 0) Thread.sleep(restante);
            ultimaChamadaNominatim = System.currentTimeMillis();
            return true;
        } finally { limiteGeocoder.unlock(); }
    }

    private List<Localizacao> consultarNominatim(String endereco, Double cLat, Double cLng) {
        try {
            if (!throttleNominatim()) return List.of();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resp = nominatim.get().uri(uri -> {
                uri.path("/search").queryParam("q", endereco).queryParam("format", "jsonv2")
                    .queryParam("limit", 5).queryParam("addressdetails", 1).queryParam("countrycodes", "br");
                if (cLat != null && cLng != null) {
                    double box = 0.5;
                    uri.queryParam("viewbox", (cLng-box)+","+(cLat+box)+","+(cLng+box)+","+(cLat-box))
                        .queryParam("bounded", 1);
                }
                return uri.build();
            }).retrieve().body(List.class);
            if (resp == null) return List.of();
            return resp.stream().map(EntregaService::lerLocalizacao).filter(java.util.Objects::nonNull).toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (RestClientException e) {
            log.warn("[Geocoder] serviço de localização indisponível: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    static Localizacao lerLocalizacao(Map<String, Object> item) {
        try {
            int rank = Integer.parseInt(String.valueOf(item.getOrDefault("place_rank", "0")));
            if (rank < 26 || !(item.get("address") instanceof Map<?, ?> endereco)) return null;
            double lat = Double.parseDouble(String.valueOf(item.get("lat")));
            double lng = Double.parseDouble(String.valueOf(item.get("lon")));
            validarCoordenadas(lat, lng);
            List<String> bairros = new ArrayList<>();
            for (String campo : List.of("suburb", "neighbourhood", "quarter", "city_district")) {
                Object valor = endereco.get(campo);
                if (valor != null && !valor.toString().isBlank()) bairros.add(valor.toString());
            }
            String cidade = "";
            for (String campo : List.of("city", "town", "municipality", "village")) {
                if (endereco.get(campo) != null) { cidade = endereco.get(campo).toString(); break; }
            }
            String uf = endereco.get("ISO3166-2-lvl4") == null ? "" : endereco.get("ISO3166-2-lvl4").toString();
            boolean numero = endereco.get("house_number") != null;
            double incerteza = Double.POSITIVE_INFINITY;
            if (item.get("boundingbox") instanceof List<?> box && box.size() == 4) {
                double sul = Double.parseDouble(box.get(0).toString()), norte = Double.parseDouble(box.get(1).toString());
                double oeste = Double.parseDouble(box.get(2).toString()), leste = Double.parseDouble(box.get(3).toString());
                validarCoordenadas(sul, oeste); validarCoordenadas(norte, leste);
                incerteza = Math.max(Math.max(distanciaHaversineKm(lat,lng,sul,oeste),distanciaHaversineKm(lat,lng,sul,leste)),
                        Math.max(distanciaHaversineKm(lat,lng,norte,oeste),distanciaHaversineKm(lat,lng,norte,leste)));
            }
            return new Localizacao(lat, lng, numero && rank >= 28 ? "imovel" : "rua", bairros, cidade, uf, numero ? endereco.get("house_number").toString() : "", incerteza,
                    endereco.get("road") == null ? "" : endereco.get("road").toString(),
                    endereco.get("postcode") == null ? "" : endereco.get("postcode").toString());
        } catch (IllegalArgumentException e) { return null; }
    }

    private static BigDecimal decOf(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n && Double.isFinite(n.doubleValue())) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return null; }
    }
}
