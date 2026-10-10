package com.mydelivery.controller;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.EntregaService;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints do módulo de entrega por raio.
 *
 * <p>Painel (autenticado):
 * <ul>
 *   <li>{@code GET  /api/restaurante/entrega/config}      — modo + coords + zonas</li>
 *   <li>{@code PUT  /api/restaurante/entrega/config}      — salva modo + coords</li>
 *   <li>{@code PUT  /api/restaurante/entrega/zonas}       — substitui zonas</li>
 * </ul>
 *
 * <p>Público (cliente final no checkout):
 * <ul>
 *   <li>{@code GET  /api/publico/geocodificar}            — {@code ?q=} texto → lat/lng</li>
 *   <li>{@code GET  /api/publico/entrega/taxa}            — {@code ?slug=&lat=&lng=} → taxa aplicável</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class EntregaController {

    private final EntregaService entregaService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.mydelivery.service.RotaEntregaService rotaService;
    private final RestauranteRepository restauranteRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.mydelivery.service.RastreamentoEntregaService rastreamentoService;

    // ═════════════════════════════════════════════════════════════════════
    // PAINEL
    // ═════════════════════════════════════════════════════════════════════

    @GetMapping("/api/restaurante/entrega/config")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> obterConfig(@AuthenticationPrincipal String email) {
        Restaurante r = getRestaurante(email);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modoTaxa", r.getModoTaxa() == null ? "BAIRRO" : r.getModoTaxa());
        out.put("latitude", r.getEnderecoLatitude());
        out.put("longitude", r.getEnderecoLongitude());
        out.put("zonas", entregaService.listarZonas(r.getId()).stream().map(z -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", z.getId());
            m.put("raioKm", z.getRaioKm());
            m.put("taxa", z.getTaxa());
            m.put("ordem", z.getOrdem());
            return m;
        }).toList());
        return ResponseEntity.ok(out);
    }

    @PutMapping("/api/restaurante/entrega/config")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> salvarConfig(@AuthenticationPrincipal String email,
                                                              @RequestBody Map<String, Object> body) {
        Restaurante r = getRestaurante(email);
        String modo = strOf(body.get("modoTaxa"));
        if (modo != null && !modo.isBlank()) {
            String m = modo.trim().toUpperCase();
            if (!"BAIRRO".equals(m) && !"RAIO".equals(m) && !"TRAJETO".equals(m)) {
                throw new IllegalArgumentException("modoTaxa inválido — use BAIRRO, RAIO ou TRAJETO");
            }
            r.setModoTaxa(m);
        }
        BigDecimal lat = decOf(body.get("latitude"));
        BigDecimal lng = decOf(body.get("longitude"));
        if (body.containsKey("latitude") || body.containsKey("longitude")) {
            if (lat == null || lng == null) throw new IllegalArgumentException("Informe latitude e longitude válidas.");
            EntregaService.validarCoordenadas(lat.doubleValue(), lng.doubleValue());
        }
        if (EntregaService.porDistancia(r) && lat == null && r.getEnderecoLatitude() == null)
            throw new IllegalArgumentException("Marque a localização exata da loja no mapa.");
        if (body.containsKey("zonas")) {
            if (!(body.get("zonas") instanceof List<?>)) throw new IllegalArgumentException("Zonas inválidas.");
            @SuppressWarnings("unchecked") var zonas = (List<Map<String, Object>>) body.get("zonas");
            if (EntregaService.porDistancia(r) && zonas.isEmpty()) throw new IllegalArgumentException("Cadastre ao menos uma zona.");
            entregaService.substituirZonas(r, zonas);
        }
        if (lat != null) r.setEnderecoLatitude(lat);
        if (lng != null) r.setEnderecoLongitude(lng);
        restauranteRepo.save(r);
        return obterConfig(email);
    }

    @GetMapping("/api/restaurante/entrega/cobertura")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public Object cobertura(@AuthenticationPrincipal String email, @RequestParam double lat,
            @RequestParam double lng,@RequestParam double km) {
        getRestaurante(email);
        return rotaService.cobertura(lat,lng,km);
    }

    @GetMapping("/api/restaurante/entrega/rastreamento")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String,Object>> obterConfigRastreamento(@AuthenticationPrincipal String email) {
        Restaurante r = getRestaurante(email);
        return ResponseEntity.ok(Map.of(
                "ativo", Boolean.TRUE.equals(r.getRastreamentoEntregaAtivo()),
                "codigoConfirmacaoAtivo", Boolean.TRUE.equals(r.getEntregaCodigoConfirmacaoAtivo())));
    }

    @PutMapping("/api/restaurante/entrega/rastreamento")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String,Object>> salvarConfigRastreamento(@AuthenticationPrincipal String email,
                                                                        @RequestBody Map<String,Object> body) {
        Restaurante r = getRestaurante(email);
        boolean estavaAtivo = Boolean.TRUE.equals(r.getRastreamentoEntregaAtivo());
        if (body.containsKey("ativo")) r.setRastreamentoEntregaAtivo(Boolean.valueOf(String.valueOf(body.get("ativo"))));
        if (body.containsKey("codigoConfirmacaoAtivo")) r.setEntregaCodigoConfirmacaoAtivo(Boolean.valueOf(String.valueOf(body.get("codigoConfirmacaoAtivo"))));
        restauranteRepo.save(r);
        if (estavaAtivo && !Boolean.TRUE.equals(r.getRastreamentoEntregaAtivo())) {
            // Desligar a opção encerra imediatamente as sessões atuais.
            rastreamentoService.revogarPorDesativacao(r);
        }
        return obterConfigRastreamento(email);
    }

    @PutMapping("/api/restaurante/entrega/zonas")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> salvarZonas(@AuthenticationPrincipal String email,
                                                             @RequestBody Map<String, Object> body) {
        Restaurante r = getRestaurante(email);
        List<Map<String, Object>> zonas = body.get("zonas") instanceof List<?>
                ? (List<Map<String, Object>>) body.get("zonas") : List.of();
        entregaService.substituirZonas(r, zonas);
        return obterConfig(email);
    }

    /**
     * Localiza a origem pelo endereço exibido no formulário do restaurante.
     * É autenticado para impedir que terceiros gastem a cota do Google.
     */
    @GetMapping("/api/restaurante/entrega/localizar-loja")
    @PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
    public ResponseEntity<Map<String,Object>> localizarLojaAutenticada(@AuthenticationPrincipal String email,
            @RequestParam String rua, @RequestParam String numero, @RequestParam String bairro,
            @RequestParam String cidade, @RequestParam String uf, @RequestParam String cep) {
        getRestaurante(email);
        return localizarLojaInterno(rua, numero, bairro, cidade, uf, cep, true);
    }

    // ═════════════════════════════════════════════════════════════════════
    // PÚBLICO (cliente final)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Geocodifica string livre (rua + numero + bairro + cidade) pra
     * {@code {lat, lng}}. Chamado pelo cardápio quando cliente termina de
     * preencher o endereço. Retorna 404 se Nominatim não achou.
     */
    @GetMapping("/api/publico/geocodificar")
    public ResponseEntity<Map<String, Object>> geocodificar(@RequestParam("q") String query,
                                                            @RequestParam(value = "slug", required = false) String slug) {
        // Âncora: se veio o slug, usa a coordenada da loja pra o geocoder só
        // buscar PERTO dela (evita cair em cidade homônima). Sem slug, cai no
        // modo antigo (texto livre).
        Double cLat = null, cLng = null;
        if (slug != null && !slug.isBlank()) {
            Restaurante r = restauranteRepo.findBySlug(slug).orElse(null);
            if (r != null && r.getEnderecoLatitude() != null && r.getEnderecoLongitude() != null) {
                cLat = r.getEnderecoLatitude().doubleValue();
                cLng = r.getEnderecoLongitude().doubleValue();
            }
        }
        var locais = entregaService.localizar(query, cLat, cLng);
        if (locais.isEmpty()) return ResponseEntity.notFound().build();
        var c = locais.getFirst();
        return ResponseEntity.ok(Map.of("lat", c.lat(), "lng", c.lng(), "precisao", c.precisao()));
    }

    @GetMapping("/api/publico/entrega/localizar-loja")
    public ResponseEntity<Map<String,Object>> localizarLoja(@RequestParam String rua, @RequestParam String numero,
            @RequestParam String bairro, @RequestParam String cidade, @RequestParam String uf, @RequestParam String cep) {
        return localizarLojaInterno(rua, numero, bairro, cidade, uf, cep, false);
    }

    private ResponseEntity<Map<String,Object>> localizarLojaInterno(String rua, String numero,
            String bairro, String cidade, String uf, String cep, boolean preferirGoogle) {
        if (rua.isBlank() || bairro.isBlank() || cidade.isBlank() || uf.length()!=2
                || rua.length()>200 || numero.length()>120 || bairro.length()>120 || cidade.length()>120 || cep.length()>10)
            return ResponseEntity.badRequest().build();
        String base = rua.trim()+", "+numero.trim();
        String regiao = ", "+cidade.trim()+", "+uf;
        String cepConsulta = cep == null || cep.isBlank() ? "" : ", "+cep.trim();
        java.util.function.Function<String,List<EntregaService.Localizacao>> localizar = preferirGoogle
                ? entregaService::localizarEnderecoLoja
                : q -> entregaService.localizar(q, null, null);
        var locais = localizar.apply(base+", "+bairro.trim()+regiao+cepConsulta);
        var compativeis = locais.stream().filter(l -> EntregaService.correspondeEndereco(l,rua,numero,bairro,cidade,uf,cep)).toList();
        // A busca sem bairro só serve para recuperar a grafia do provedor.
        // A validação de bairro, CEP, cidade, UF e rua continua obrigatória.
        if (compativeis.isEmpty()) compativeis = localizar.apply(base+regiao+cepConsulta).stream()
                .filter(l -> EntregaService.correspondeEndereco(l,rua,numero,bairro,cidade,uf,cep)).toList();
        // O número pode não existir no mapa. Primeiro tenta a rua com os mesmos
        // critérios; depois aceita variação mínima de grafia só para conferência.
        if (compativeis.isEmpty()) compativeis = localizar.apply(rua.trim()+", "+bairro.trim()+regiao+cepConsulta).stream()
                .filter(l -> "rua".equals(l.precisao()) && EntregaService.correspondeEndereco(l,rua,numero,bairro,cidade,uf,cep)).toList();
        if (compativeis.isEmpty()) compativeis = entregaService.localizarRuaParaConferencia(rua,bairro,cidade,uf);
        if (compativeis.isEmpty()) return ResponseEntity.notFound().build();
        var local = compativeis.getFirst();
        if (compativeis.stream().anyMatch(l -> EntregaService.distanciaHaversineKm(local.lat(),local.lng(),l.lat(),l.lng())>0.1))
            return ResponseEntity.unprocessableEntity().build();
        return ResponseEntity.ok(Map.of("lat",local.lat(),"lng",local.lng(),"precisao",local.precisao()));
    }

    /**
     * Retorna a taxa aplicável pro cliente no destino informado.
     * {@code slug} identifica o restaurante. Retorna {@code taxa=null} se
     * fora de raio ou restaurante em modo BAIRRO (front deve usar o fluxo
     * clássico de bairro nesse caso).
     */
    @GetMapping("/api/publico/entrega/taxa")
    public ResponseEntity<Map<String, Object>> taxaPorRaio(@RequestParam String slug,
                                                             @RequestParam double lat,
                                                             @RequestParam double lng) {
        Restaurante r = restauranteRepo.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modoTaxa", r.getModoTaxa() == null ? "BAIRRO" : r.getModoTaxa());
        if (!EntregaService.porDistancia(r)) {
            out.put("taxa", null);
            out.put("mensagem", "Restaurante cobra por bairro — use o fluxo padrão");
            return ResponseEntity.ok(out);
        }
        BigDecimal taxa = entregaService.calcularTaxaPorRaio(r, lat, lng);
        out.put("taxa", taxa);
        if (taxa == null) {
            out.put("mensagem", "Fora da área de entrega");
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/publico/entrega/localizar")
    public ResponseEntity<Map<String, Object>> localizarEndereco(@RequestParam String slug,
            @RequestParam String rua, @RequestParam String numero, @RequestParam String bairro,
            @RequestParam(defaultValue="") String cidade, @RequestParam(defaultValue="") String uf,
            @RequestParam(defaultValue="") String cep) {
        Restaurante r = restauranteRepo.findBySlug(slug).orElseThrow();
        if(bairro.isBlank() || bairro.length()>120) return ResponseEntity.badRequest().build();
        // Compatibilidade com cardápios já abertos antes da atualização: o bairro
        // também define a taxa final nessa rota antiga, sem geocodificar rua/número.
        var cotacao="TRAJETO".equalsIgnoreCase(r.getModoTaxa())
                ? entregaService.consultarTaxaEndereco(r,rua,numero,bairro,cidade,uf,cep)
                : entregaService.consultarTaxaBairro(r,bairro);
        if(cotacao.lat()==null || cotacao.lng()==null) return ResponseEntity.notFound().build();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("lat",cotacao.lat());out.put("lng",cotacao.lng());out.put("bairro",cotacao.bairro());
        out.put("localizado",true);out.put("taxa",cotacao.taxa());
        return ResponseEntity.ok(out);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Restaurante getRestaurante(String email) {
        return restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
    }
    private static String strOf(Object o) { return o == null ? null : o.toString(); }
    private static BigDecimal decOf(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return null; }
    }
}
