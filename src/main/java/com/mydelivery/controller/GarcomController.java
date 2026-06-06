package com.mydelivery.controller;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.model.Mesa;
import com.mydelivery.model.MesaSessao;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.PedidoItem;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.model.UsuarioGarcom;
import com.mydelivery.repository.MesaRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.repository.UsuarioGarcomRepository;
import com.mydelivery.security.JwtUtil;
import com.mydelivery.service.GarcomService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * API do garçom — operação no salão.
 *
 * Fluxo de autenticação:
 *   1) POST /api/garcom/{slugRestaurante}/login  body: { pin }  →  JWT
 *   2) Bearer token nas próximas chamadas, role=GARCOM
 *
 * O JWT do garçom carrega subject "garcom:{garcomId}:{restauranteId}"
 * pra evitar collision com o JWT do dono (subject=email do user).
 * Helpers nesse controller parseiam isso pra recuperar contexto.
 *
 * Endpoints (autenticados como GARCOM):
 *   GET  /api/garcom/mapa
 *   GET  /api/garcom/mesa/{slug}
 *   POST /api/garcom/mesa/{slug}/abrir
 *   POST /api/garcom/mesa/{slug}/pedido
 *   POST /api/garcom/mesa/{slug}/fechar
 *
 * Cadastro de garçons (autenticado como dono RESTAURANTE):
 *   GET    /api/restaurante/garcons
 *   POST   /api/restaurante/garcons        body: { nome, pin }
 *   DELETE /api/restaurante/garcons/{id}
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GarcomController {

    private final GarcomService garcomService;
    private final RestauranteRepository restauranteRepo;
    private final UsuarioGarcomRepository garcomRepo;
    private final PedidoRepository pedidoRepo;
    private final ProdutoRepository produtoRepo;
    private final MesaRepository mesaRepo;
    private final JwtUtil jwtUtil;

    // ═══════════════════════════════════════════════════════════════════
    // LOGIN DO GARÇOM (PÚBLICO — usa PIN)
    // ═══════════════════════════════════════════════════════════════════

    @PostMapping("/api/garcom/{slugRestaurante}/login")
    public ResponseEntity<Map<String, Object>> login(
            @PathVariable String slugRestaurante,
            @RequestBody Map<String, String> body) {
        Restaurante r = restauranteRepo.findBySlug(slugRestaurante)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Restaurante não encontrado"));
        String pin = body == null ? null : body.get("pin");
        var opt = garcomService.autenticar(r.getId(), pin);
        if (opt.isEmpty()) {
            // Não vaza nem se restaurante existe nem qual garçom — mesma resposta.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "PIN incorreto");
        }
        UsuarioGarcom g = opt.get();
        // Subject sintético com restauranteId pra recuperar contexto sem nova query.
        String subject = "garcom:" + g.getId() + ":" + r.getId();
        String token = jwtUtil.gerarToken(subject, "GARCOM");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("nome", g.getNome());
        resp.put("garcomId", g.getId());
        resp.put("restauranteSlug", r.getSlug());
        resp.put("restauranteNome", r.getNome());
        return ResponseEntity.ok(resp);
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPERAÇÃO DO GARÇOM (auth role=GARCOM)
    // ═══════════════════════════════════════════════════════════════════

    @GetMapping("/api/garcom/mapa")
    @PreAuthorize("hasRole('GARCOM')")
    public ResponseEntity<List<Map<String, Object>>> mapa(@AuthenticationPrincipal String subject) {
        Ctx ctx = parseSubject(subject);
        return ResponseEntity.ok(garcomService.mapaSalao(ctx.restauranteId));
    }

    @GetMapping("/api/garcom/mesa/{slug}")
    @PreAuthorize("hasRole('GARCOM')")
    public ResponseEntity<Map<String, Object>> detalheMesa(@AuthenticationPrincipal String subject,
                                                            @PathVariable String slug) {
        Ctx ctx = parseSubject(subject);
        var sessaoOpt = garcomService.sessaoDaMesa(ctx.restauranteId, slug);
        Map<String, Object> out = new LinkedHashMap<>();
        if (sessaoOpt.isEmpty()) {
            out.put("ocupada", false);
            return ResponseEntity.ok(out);
        }
        MesaSessao s = sessaoOpt.get();
        out.put("ocupada", true);
        out.put("sessaoId", s.getId());
        out.put("nomeCliente", s.getNomeCliente());
        out.put("telefoneCliente", s.getTelefoneCliente());
        out.put("pessoas", s.getPessoas());
        out.put("status", s.getStatus().name());
        out.put("aberturaEm", s.getAberturaEm().toString());
        out.put("totalAcumulado", s.getTotalAcumulado());
        // Lista de pedidos da sessão
        var pedidos = pedidoRepo.findBySessaoIdOrderByCriadoEmAsc(s.getId());
        var resumoPedidos = pedidos.stream().map(this::resumirPedido).toList();
        out.put("pedidos", resumoPedidos);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/garcom/mesa/{slug}/abrir")
    @PreAuthorize("hasRole('GARCOM')")
    public ResponseEntity<Map<String, Object>> abrirMesa(
            @AuthenticationPrincipal String subject,
            @PathVariable String slug,
            @RequestBody(required = false) Map<String, Object> body) {
        Ctx ctx = parseSubject(subject);
        String nome = body == null ? null : strOf(body.get("nomeCliente"));
        String tel = body == null ? null : strOf(body.get("telefoneCliente"));
        Integer pessoas = body == null ? null : intOf(body.get("pessoas"));
        var sessao = garcomService.abrirSessao(ctx.restauranteId, slug, ctx.garcomId, nome, pessoas, tel);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "sessaoId", sessao.getId(),
                "status", sessao.getStatus().name()
        ));
    }

    /**
     * Cria pedido pelo garçom: itens vêm pré-validados (id do produto + qtd),
     * vínculo com sessão atual. Subtotal/total calculados aqui (não confia em
     * valor do client). Tipo sempre MESA. Status CONFIRMADO (já vai pra cozinha).
     */
    @PostMapping("/api/garcom/mesa/{slug}/pedido")
    @PreAuthorize("hasRole('GARCOM')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> criarPedido(
            @AuthenticationPrincipal String subject,
            @PathVariable String slug,
            @RequestBody Map<String, Object> body) {
        Ctx ctx = parseSubject(subject);
        // Garante sessão aberta (idempotente)
        var sessao = garcomService.abrirSessao(ctx.restauranteId, slug, ctx.garcomId, null, null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itensReq = (List<Map<String, Object>>) body.get("itens");
        if (itensReq == null || itensReq.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "itens é obrigatório");
        }
        String observacao = strOf(body.get("observacao"));
        Integer pessoaIndice = intOf(body.get("pessoaIndice"));

        Restaurante r = restauranteRepo.findById(ctx.restauranteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // Pega a mesa pra preencher o FK do pedido (relatórios e comanda da
        // mesa filtram por pedido.mesa, então é importante setar).
        Mesa mesa = mesaRepo.findByRestauranteIdAndSlug(ctx.restauranteId, slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mesa não encontrada"));

        Pedido p = new Pedido();
        p.setRestaurante(r);
        p.setMesa(mesa);
        p.setTipo(Pedido.Tipo.MESA);
        // Garçom já lança CONFIRMADO — vai direto pra cozinha. EM_PREPARO é setado
        // quando a cozinha pega; ENTREGUE quando garçom serve.
        p.setStatus(Pedido.Status.CONFIRMADO);
        // Pagamento real é feito no fim. Default DINHEIRO; cliente pode pagar de
        // outra forma na hora do fechamento. ModoPagamento sempre NA_ENTREGA aqui.
        p.setFormaPagamento(Pedido.FormaPagamento.DINHEIRO);
        p.setModoPagamento(Pedido.ModoPagamento.NA_ENTREGA);
        p.setSessaoId(sessao.getId());
        p.setGarcomId(ctx.garcomId);
        p.setPessoaIndice(pessoaIndice);
        p.setObservacao(observacao);
        p.setTaxaEntrega(BigDecimal.ZERO);
        p.setDesconto(BigDecimal.ZERO);

        BigDecimal subtotal = BigDecimal.ZERO;
        List<PedidoItem> itens = new ArrayList<>();
        for (var it : itensReq) {
            Long produtoId = longOf(it.get("produtoId"));
            int qtd = intOf(it.get("quantidade")) == null ? 1 : intOf(it.get("quantidade"));
            if (qtd < 1) qtd = 1;
            String obsItem = strOf(it.get("observacao"));
            Produto prod = produtoRepo.findById(produtoId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Produto " + produtoId + " inválido"));
            if (!prod.getRestaurante().getId().equals(ctx.restauranteId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Produto fora do restaurante");
            }
            BigDecimal precoUnit = prod.getPreco() == null ? BigDecimal.ZERO : prod.getPreco();
            BigDecimal totalItem = precoUnit.multiply(BigDecimal.valueOf(qtd));
            subtotal = subtotal.add(totalItem);

            PedidoItem pi = new PedidoItem();
            pi.setPedido(p);
            pi.setProduto(prod);
            pi.setNomeProduto(prod.getNome());
            pi.setQuantidade(qtd);
            pi.setPrecoUnitario(precoUnit);
            pi.setSubtotal(totalItem);
            pi.setObservacao(obsItem);
            itens.add(pi);
        }
        p.setSubtotal(subtotal);
        p.setTotal(subtotal);
        p.setItens(itens);

        Pedido salvo = pedidoRepo.save(p);

        // Atualiza estado da sessão
        sessao.setStatus(MesaSessao.Status.PEDIDO_ENVIADO);
        sessao.setUltimaInteracaoEm(LocalDateTime.now());
        sessao.setTotalAcumulado(sessao.getTotalAcumulado().add(subtotal));
        // sessaoRepo não tá injetado aqui — atualiza via service
        garcomService.atualizarStatus(sessao.getId(), MesaSessao.Status.PEDIDO_ENVIADO);

        log.info("[Garçom] Pedido #{} criado mesa={} garcom={} total=R${}",
                salvo.getId(), slug, ctx.garcomId, subtotal);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "ok", true,
                "pedidoId", salvo.getId(),
                "total", salvo.getTotal()
        ));
    }

    /**
     * Divisão PROPORCIONAL da conta — diferencial real.
     *
     * Agrupa os pedidos da sessão por pessoa_indice. Pedidos sem indice
     * (coletivos) são rateados igualmente entre as pessoas. Soma 10% de
     * taxa de serviço por padrão (garçom desmarca individualmente se
     * cliente recusar).
     *
     * Retorno: [{ pessoa: 1|2|null, subtotal, taxa10, total, itens: [...] }]
     */
    @GetMapping("/api/garcom/mesa/{slug}/divisao")
    @PreAuthorize("hasRole('GARCOM')")
    public ResponseEntity<Map<String, Object>> divisao(
            @AuthenticationPrincipal String subject,
            @PathVariable String slug) {
        Ctx ctx = parseSubject(subject);
        var sessaoOpt = garcomService.sessaoDaMesa(ctx.restauranteId, slug);
        if (sessaoOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("ocupada", false));
        }
        var sessao = sessaoOpt.get();
        int pessoasTotal = sessao.getPessoas() == null ? 1 : sessao.getPessoas();
        var pedidos = pedidoRepo.findBySessaoIdOrderByCriadoEmAsc(sessao.getId());

        // Agrupa: pessoaIndice → lista de itens + subtotal
        java.util.Map<Integer, java.util.List<Map<String, Object>>> porPessoa = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, java.math.BigDecimal> subtotalPessoa = new java.util.LinkedHashMap<>();
        java.math.BigDecimal subtotalColetivo = java.math.BigDecimal.ZERO;
        java.util.List<Map<String, Object>> itensColetivos = new java.util.ArrayList<>();

        for (var p : pedidos) {
            if (p.getStatus() == com.mydelivery.model.Pedido.Status.CANCELADO) continue;
            for (var it : p.getItens()) {
                java.math.BigDecimal vit = it.getSubtotal() == null ? java.math.BigDecimal.ZERO : it.getSubtotal();
                Map<String, Object> itDto = new java.util.LinkedHashMap<>();
                itDto.put("nome", it.getNomeProduto());
                itDto.put("quantidade", it.getQuantidade());
                itDto.put("subtotal", vit);

                if (p.getPessoaIndice() == null) {
                    subtotalColetivo = subtotalColetivo.add(vit);
                    itensColetivos.add(itDto);
                } else {
                    int idx = p.getPessoaIndice();
                    porPessoa.computeIfAbsent(idx, k -> new java.util.ArrayList<>()).add(itDto);
                    subtotalPessoa.merge(idx, vit, java.math.BigDecimal::add);
                }
            }
        }

        // Rateio coletivo: divide igualmente entre TODAS as pessoas (1..pessoasTotal),
        // mesmo as que não tem itens individuais.
        java.math.BigDecimal rateio = pessoasTotal > 0
                ? subtotalColetivo.divide(java.math.BigDecimal.valueOf(pessoasTotal), 2, java.math.RoundingMode.HALF_UP)
                : java.math.BigDecimal.ZERO;

        java.util.List<Map<String, Object>> pessoas = new java.util.ArrayList<>();
        for (int i = 1; i <= pessoasTotal; i++) {
            java.math.BigDecimal sub = subtotalPessoa.getOrDefault(i, java.math.BigDecimal.ZERO).add(rateio);
            java.math.BigDecimal taxa10 = sub.multiply(new java.math.BigDecimal("0.10")).setScale(2, java.math.RoundingMode.HALF_UP);
            Map<String, Object> p = new java.util.LinkedHashMap<>();
            p.put("pessoa", i);
            p.put("subtotal", sub);
            p.put("taxaSugerida", taxa10);
            p.put("totalComTaxa", sub.add(taxa10));
            p.put("itens", porPessoa.getOrDefault(i, java.util.List.of()));
            pessoas.add(p);
        }

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ocupada", true);
        out.put("pessoasTotal", pessoasTotal);
        out.put("totalSessao", sessao.getTotalAcumulado());
        out.put("subtotalColetivo", subtotalColetivo);
        out.put("rateioPorPessoa", rateio);
        out.put("itensColetivos", itensColetivos);
        out.put("pessoas", pessoas);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/garcom/mesa/{slug}/fechar")
    @PreAuthorize("hasRole('GARCOM')")
    public ResponseEntity<Map<String, Object>> fecharMesa(
            @AuthenticationPrincipal String subject,
            @PathVariable String slug) {
        Ctx ctx = parseSubject(subject);
        var sessaoOpt = garcomService.sessaoDaMesa(ctx.restauranteId, slug);
        if (sessaoOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("ok", true, "mensagem", "mesa já estava livre"));
        }
        var fechada = garcomService.fecharSessao(sessaoOpt.get().getId(), ctx.garcomId);
        return ResponseEntity.ok(Map.of("ok", true, "status", fechada.getStatus().name()));
    }

    // ═══════════════════════════════════════════════════════════════════
    // CADASTRO DE GARÇONS (auth role=RESTAURANTE)
    // ═══════════════════════════════════════════════════════════════════

    @GetMapping("/api/restaurante/garcons")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> listarGarcons(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        var lista = garcomRepo.findByRestauranteIdOrderByNomeAsc(r.getId());
        var out = lista.stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("nome", g.getNome());
            m.put("ativo", g.getAtivo());
            m.put("criadoEm", g.getCriadoEm() == null ? null : g.getCriadoEm().toString());
            // PIN nunca devolvido
            return m;
        }).toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/restaurante/garcons")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> criarGarcom(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        try {
            UsuarioGarcom g = garcomService.criarGarcom(r.getId(),
                    body.get("nome"), body.get("pin"));
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "id", g.getId(), "nome", g.getNome(), "ativo", g.getAtivo()
            ));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esse PIN já está em uso neste restaurante. Escolha outro.");
        }
    }

    @DeleteMapping("/api/restaurante/garcons/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Void> desativarGarcom(
            @AuthenticationPrincipal String email,
            @PathVariable Long id) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        garcomService.desativarGarcom(r.getId(), id);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    /** Parseia subject "garcom:{garcomId}:{restauranteId}" do JWT. */
    private Ctx parseSubject(String subject) {
        if (subject == null || !subject.startsWith("garcom:")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido");
        }
        String[] parts = subject.split(":");
        if (parts.length < 3) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token mal formado");
        }
        try {
            return new Ctx(Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private static record Ctx(Long garcomId, Long restauranteId) {}

    private String strOf(Object o) { return o == null ? null : o.toString(); }
    private Integer intOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return null; }
    }
    private Long longOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }

    private Map<String, Object> resumirPedido(Pedido p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("status", p.getStatus().name());
        m.put("total", p.getTotal());
        m.put("pessoaIndice", p.getPessoaIndice());
        m.put("criadoEm", p.getCriadoEm() == null ? null : p.getCriadoEm().toString());
        // Map.of() rejeita null. Itens vindos do cliente/balcão podem ter
        // nomeProduto/subtotal nulos — usamos HashMap pra preservar a chave
        // mesmo com null e evitar NPE -> HTTP 400 no GET /mesa/{slug}.
        m.put("itens", p.getItens().stream().map(it -> {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("nome", it.getNomeProduto());
            i.put("quantidade", it.getQuantidade());
            i.put("subtotal", it.getSubtotal());
            return i;
        }).toList());
        return m;
    }
}
