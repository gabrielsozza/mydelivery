package com.mydelivery.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.dto.cardapio.CategoriaComProdutosResponse;
import com.mydelivery.dto.cardapio.CategoriaRequest;
import com.mydelivery.dto.cardapio.ProdutoRequest;
import com.mydelivery.dto.cardapio.ProdutoResponse;
import com.mydelivery.dto.cardapio.UltimoPedidoResponse;
import com.mydelivery.model.Categoria;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.CardapioService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
public class CardapioController {

    private final CardapioService cardapioService;
    private final RestauranteRepository restauranteRepository;
    private final com.mydelivery.repository.CategoriaRepository categoriaRepository;
    private final com.mydelivery.repository.ClienteRepository clienteRepository;
    private final com.mydelivery.repository.PedidoItemRepository pedidoItemRepository;

    /**
     * Auto-preenchimento de checkout — pra cliente recorrente.
     * Quando o cliente preenche nome+telefone no início do pedido,
     * o frontend consulta esse endpoint pra ver se já temos endereço
     * registrado de um pedido anterior. Se sim, pré-preenche os campos
     * de rua/número/bairro/etc no checkout — cliente só escolhe pagamento.
     *
     * Devolve 204 (No Content) se o telefone não tem cadastro anterior
     * naquele restaurante — frontend trata como "primeiro pedido".
     *
     * Match é por telefone normalizado (só dígitos) + restaurante (multi-tenant).
     */
    @GetMapping("/api/cardapio/{slug}/cliente")
    public ResponseEntity<Map<String, Object>> buscarClienteRecorrente(
            @PathVariable String slug,
            @org.springframework.web.bind.annotation.RequestParam("telefone") String telefone) {
        if (telefone == null || telefone.isBlank()) {
            return ResponseEntity.noContent().build();
        }
        Restaurante r = restauranteRepository.findBySlug(slug).orElse(null);
        if (r == null) return ResponseEntity.noContent().build();
        String tel = com.mydelivery.util.TelefoneUtil.normalizar(telefone);
        if (tel == null || tel.length() < 10) return ResponseEntity.noContent().build();
        var opt = clienteRepository.findByTelefoneAndRestauranteId(tel, r.getId());
        if (opt.isEmpty()) return ResponseEntity.noContent().build();
        var c = opt.get();
        // Só devolve se tiver pelo menos rua + bairro — sem isso, não dá
        // pra pré-preencher de jeito útil (bairro é obrigatório pra calcular taxa).
        if (c.getEnderecoRua() == null || c.getEnderecoRua().isBlank()
                || c.getEnderecoBairro() == null || c.getEnderecoBairro().isBlank()) {
            return ResponseEntity.noContent().build();
        }
        Map<String, Object> endereco = new java.util.LinkedHashMap<>();
        endereco.put("rua", c.getEnderecoRua());
        endereco.put("numero", c.getEnderecoNumero() != null ? c.getEnderecoNumero() : "");
        endereco.put("complemento", c.getEnderecoComplemento() != null ? c.getEnderecoComplemento() : "");
        endereco.put("bairro", c.getEnderecoBairro());
        endereco.put("referencia", c.getEnderecoReferencia() != null ? c.getEnderecoReferencia() : "");
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("nome", c.getNome());
        resp.put("telefone", c.getTelefone());
        resp.put("endereco", endereco);
        return ResponseEntity.ok(resp);
    }

    /**
     * "Pedir novamente" — retorna o último pedido do cliente pelo dispositivo.
     *
     * SEGURANÇA:
     *  - Escopo composto (slug, device_uuid) — sem os DOIS não retorna nada.
     *  - Query com WHERE restaurante_id sempre presente. Impossível pegar
     *    pedido de outra loja mesmo com bug no repositório.
     *  - Retorna 204 (No Content) pra QUALQUER UUID sem match — não
     *    distingue "não existe" de "existe em outra loja".
     *  - UUID validado no formato canônico. Strings estranhas caem em 204.
     *
     * PERFORMANCE:
     *  - Lookup por índice único (restaurante_id, device_uuid) → O(log n).
     *  - Itens carregados com JOIN FETCH em produto → 1 SQL só, sem N+1.
     *  - Reconciliação de preço/disponibilidade no próprio endpoint —
     *    frontend recebe pronto pra renderizar.
     */
    @GetMapping("/api/cardapio/{slug}/cliente/{deviceUuid}/ultimo-pedido")
    public ResponseEntity<UltimoPedidoResponse> ultimoPedidoPorDispositivo(
            @PathVariable String slug,
            @PathVariable String deviceUuid) {
        if (deviceUuid == null || deviceUuid.isBlank()) return ResponseEntity.noContent().build();
        String uuid = deviceUuid.trim();
        // Anti-lixo: UUID canônico tem 32-36 chars (hex/traços). Bloqueia strings
        // malformadas antes de chegar no banco.
        if (uuid.length() < 32 || uuid.length() > 36 || !uuid.matches("[a-fA-F0-9\\-]+")) {
            return ResponseEntity.noContent().build();
        }
        Restaurante r = restauranteRepository.findBySlug(slug).orElse(null);
        if (r == null) return ResponseEntity.noContent().build();
        var cliente = clienteRepository.findByRestauranteIdAndDeviceUuid(r.getId(), uuid).orElse(null);
        if (cliente == null || cliente.getUltimoPedido() == null) {
            return ResponseEntity.noContent().build();
        }
        var pedido = cliente.getUltimoPedido();
        // Guard-rail EXTRA: tenant-check. Mesmo com FK bem posta, garante
        // que ninguém consiga puxar pedido cross-restaurante por mais que
        // tente manipular o UUID.
        if (pedido.getRestaurante() == null
                || !pedido.getRestaurante().getId().equals(r.getId())) {
            return ResponseEntity.noContent().build();
        }
        var itens = pedidoItemRepository.findByPedidoIdComProduto(pedido.getId());
        if (itens == null || itens.isEmpty()) return ResponseEntity.noContent().build();

        UltimoPedidoResponse resp = new UltimoPedidoResponse();
        resp.setNome(cliente.getNome());
        resp.setTelefone(cliente.getTelefone());
        UltimoPedidoResponse.EnderecoDto end = new UltimoPedidoResponse.EnderecoDto();
        end.setRua(cliente.getEnderecoRua());
        end.setNumero(cliente.getEnderecoNumero());
        end.setComplemento(cliente.getEnderecoComplemento());
        end.setBairro(cliente.getEnderecoBairro());
        end.setCidade(cliente.getEnderecoCidade());
        end.setEstado(cliente.getEnderecoEstado());
        end.setCep(cliente.getEnderecoCep());
        end.setReferencia(cliente.getEnderecoReferencia());
        resp.setEndereco(end);
        resp.setPedidoId(pedido.getId());
        resp.setPedidoEm(pedido.getCriadoEm());
        resp.setTotalAnterior(pedido.getTotal());

        boolean algumMudou = false;
        boolean algumRemovido = false;
        java.math.BigDecimal totalAtual = java.math.BigDecimal.ZERO;
        java.util.List<UltimoPedidoResponse.ItemDto> itensOut = new java.util.ArrayList<>();
        for (var it : itens) {
            UltimoPedidoResponse.ItemDto d = new UltimoPedidoResponse.ItemDto();
            d.setPedidoItemId(it.getId());
            d.setNome(it.getNomeProduto());
            d.setQuantidade(it.getQuantidade());
            d.setPrecoOriginal(it.getPrecoUnitario());
            d.setObservacao(it.getObservacao());
            var prod = it.getProduto();
            boolean disponivel = prod != null
                    && Boolean.TRUE.equals(prod.getDisponivel())
                    && prod.getRestaurante() != null
                    && prod.getRestaurante().getId().equals(r.getId());
            d.setDisponivel(disponivel);
            if (disponivel) {
                d.setProdutoId(prod.getId());
                d.setFotoUrl(prod.getFotoUrl());
                d.setPrecoAtual(prod.getPreco());
                boolean mudou = it.getPrecoUnitario() != null
                        && prod.getPreco() != null
                        && prod.getPreco().compareTo(it.getPrecoUnitario()) != 0;
                d.setPrecoMudou(mudou);
                if (mudou) algumMudou = true;
                totalAtual = totalAtual.add(prod.getPreco()
                        .multiply(java.math.BigDecimal.valueOf(it.getQuantidade())));
            } else {
                d.setPrecoAtual(it.getPrecoUnitario());
                d.setPrecoMudou(false);
                algumRemovido = true;
            }
            itensOut.add(d);
        }
        resp.setItens(itensOut);
        resp.setTotalAtual(totalAtual);
        resp.setAlgumPrecoMudou(algumMudou);
        resp.setAlgumItemRemovido(algumRemovido);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(resp);
    }

    // ─── PÚBLICO ──────────────────────────────────────────────────────────
    @GetMapping("/api/cardapio/{slug}")
    public ResponseEntity<List<CategoriaComProdutosResponse>> getCardapioPublico(
            @PathVariable String slug) {
        // no-cache: garante que alterações de produto/categoria/foto feitas no
        // painel apareçam no cardápio do cliente sem precisar de Ctrl+F5.
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .body(cardapioService.getCardapioPublico(slug));
    }

    @GetMapping("/api/restaurante/publico/{slug}/horarios")
    public ResponseEntity<?> horariosDisponiveis(@PathVariable String slug) {
        Restaurante r = restauranteRepository.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return ResponseEntity.ok(Map.of(
                "ativo",        Boolean.TRUE.equals(r.getAgendamentoAtivo()),
                "slots",        r.getAgendamentoSlots() != null ? r.getAgendamentoSlots() : List.of(),
                "intervalo",    r.getAgendamentoIntervalo() != null ? r.getAgendamentoIntervalo() : 30,
                "antecedencia", r.getAgendamentoAntecedencia() != null ? r.getAgendamentoAntecedencia() : 1
        ));
    }

    // ─── PRIVADO ──────────────────────────────────────────────────────────
    @GetMapping("/api/restaurante/{slug}/categorias")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Categoria>> getCategorias(
            @PathVariable String slug,
            @AuthenticationPrincipal String email) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.getCategorias(restauranteId));
    }

    @PostMapping("/api/restaurante/{slug}/categorias")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Categoria> criarCategoria(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @Valid @RequestBody CategoriaRequest request) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.criarCategoria(restauranteId, request));
    }

    /** Body: [id1, id2, id3, ...] na nova ordem. Multi-tenant (filtra por restaurante do email). */
    @PutMapping("/api/restaurante/{slug}/categorias/reordenar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<java.util.Map<String, Object>> reordenarCategorias(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @RequestBody java.util.List<Long> idsNaOrdem) {
        Long restauranteId = getRestauranteId(email);
        var existentes = categoriaRepository.findByRestauranteIdOrderByOrdemAsc(restauranteId);
        var porId = new java.util.HashMap<Long, com.mydelivery.model.Categoria>();
        for (var c : existentes) porId.put(c.getId(), c);
        int ord = 0;
        for (Long id : idsNaOrdem) {
            var c = porId.get(id);
            if (c == null) continue; // ignora IDs estranhos (multi-tenant safe)
            c.setOrdem(ord++);
        }
        categoriaRepository.saveAll(porId.values());
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }

    @PutMapping("/api/restaurante/{slug}/categorias/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Categoria> atualizarCategoria(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @Valid @RequestBody CategoriaRequest request) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.atualizarCategoria(restauranteId, id, request));
    }

    @DeleteMapping("/api/restaurante/{slug}/categorias/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @com.mydelivery.equipe.PermissaoRequerida(com.mydelivery.equipe.Permissao.EXCLUIR_CATEGORIAS)
    public ResponseEntity<Void> deletarCategoria(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id) {
        Long restauranteId = getRestauranteId(email);
        cardapioService.deletarCategoria(restauranteId, id);
        return ResponseEntity.noContent().build();
    }

    /** Duplica uma categoria com TODOS os produtos dentro (incluindo grupos
     *  de complementos e itens). Atômico — se algum produto falhar, nada salva.
     *  Útil pra restaurante criar variantes ("Açaí", "Açaí Promocional", etc)
     *  sem refazer cadastro item por item. */
    @PostMapping("/api/restaurante/{slug}/categorias/{id}/duplicar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Categoria> duplicarCategoria(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.duplicarCategoria(restauranteId, id));
    }

    /** Duplica um produto único (com complementos). Produto novo nasce
     *  INATIVO e na MESMA categoria do original. Mesma lógica do
     *  duplicar categoria mas em escala unitária. */
    @PostMapping("/api/restaurante/{slug}/produtos/{id}/duplicar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<?> duplicarProduto(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestParam(value = "quantidade", required = false, defaultValue = "1") Integer quantidade) {
        Long restauranteId = getRestauranteId(email);
        try {
            int qtd = quantidade == null ? 1 : Math.max(1, Math.min(20, quantidade));
            var lista = cardapioService.duplicarProdutoNVezes(restauranteId, id, qtd);
            // Retrocompat: quando qtd=1, devolve o produto direto (formato antigo).
            // Quando qtd>1, devolve array.
            if (qtd == 1) return ResponseEntity.ok(lista.get(0));
            return ResponseEntity.ok(lista);
        } catch (Exception e) {
            // Retorna a msg do erro pra front conseguir mostrar algo util
            // (antes ficava so 409 "Conflict" sem contexto).
            log.error("[Cardapio] duplicar produto {} falhou: {}", id, e.getMessage(), e);
            String msg = e.getMessage() == null ? "Falha ao duplicar" : e.getMessage();
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST)
                    .body(java.util.Map.of("erro", msg));
        }
    }

    @GetMapping("/api/restaurante/{slug}/categorias/{categoriaId}/produtos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<ProdutoResponse>> getProdutosPorCategoria(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long categoriaId) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.getProdutosPorCategoria(restauranteId, categoriaId));
    }

    @GetMapping("/api/restaurante/{slug}/produtos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<ProdutoResponse>> getProdutos(
            @PathVariable String slug,
            @AuthenticationPrincipal String email) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.getProdutos(restauranteId));
    }

    @PostMapping("/api/restaurante/{slug}/produtos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<ProdutoResponse> criarProduto(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @Valid @RequestBody ProdutoRequest request) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.criarProduto(restauranteId, request));
    }

    @PutMapping("/api/restaurante/{slug}/produtos/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<ProdutoResponse> atualizarProduto(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @Valid @RequestBody ProdutoRequest request) {
        Long restauranteId = getRestauranteId(email);
        return ResponseEntity.ok(cardapioService.atualizarProduto(restauranteId, id, request));
    }

    @DeleteMapping("/api/restaurante/{slug}/produtos/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @com.mydelivery.equipe.PermissaoRequerida(com.mydelivery.equipe.Permissao.EXCLUIR_PRODUTOS)
    public ResponseEntity<Void> deletarProduto(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id) {
        Long restauranteId = getRestauranteId(email);
        cardapioService.deletarProduto(restauranteId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Toggle de disponibilidade do produto (sem precisar mandar o ProdutoRequest
     * inteiro, que exige nome/preco/categoria etc). Caso de uso: o dono
     * pausa um produto que acabou no estoque, depois reativa.
     *
     * Aceita body em duas variacoes pra compat com clientes diferentes:
     *   { "disponivel": true|false }   ← preferido
     *   { "ativo":      true|false }   ← alias (frontend antigo)
     *
     * Multi-tenant safe — checa que o produto pertence ao restaurante do email.
     */
    @PatchMapping("/api/restaurante/{slug}/produtos/{id}/disponivel")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<ProdutoResponse> atualizarDisponibilidade(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody java.util.Map<String, Object> body) {
        Long restauranteId = getRestauranteId(email);
        Object v = body.get("disponivel");
        if (v == null) v = body.get("ativo");
        boolean disponivel = (v instanceof Boolean b) ? b
                : v != null && Boolean.parseBoolean(v.toString());
        return ResponseEntity.ok(cardapioService.atualizarDisponibilidade(restauranteId, id, disponivel));
    }

    /** Alias /ativo — frontend antigo (cardapio.html) chamava este path.
     *  Mantemos pra nao quebrar a versao cacheada no browser do dono. */
    @PatchMapping("/api/restaurante/{slug}/produtos/{id}/ativo")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<ProdutoResponse> atualizarAtivoAlias(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody java.util.Map<String, Object> body) {
        return atualizarDisponibilidade(slug, email, id, body);
    }

    /** Body: [id1, id2, ...] na nova ordem dos produtos da categoria. Multi-tenant safe. */
    @PutMapping("/api/restaurante/{slug}/categorias/{categoriaId}/produtos/reordenar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> reordenarProdutos(
            @PathVariable String slug,
            @AuthenticationPrincipal String email,
            @PathVariable Long categoriaId,
            @RequestBody List<Long> idsNaOrdem) {
        Long restauranteId = getRestauranteId(email);
        cardapioService.reordenarProdutosNaCategoria(restauranteId, categoriaId, idsNaOrdem);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ─── Helper ───────────────────────────────────────────────────────────
    private Long getRestauranteId(String email) {
        return restauranteRepository
                .findByUsuarioEmail(email)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"))
                .getId();
    }
}
