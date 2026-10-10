package com.mydelivery.controller;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.dto.pedido.NovoPedidoRequest;
import com.mydelivery.dto.publico.CategoriaPublicResponse;
import com.mydelivery.dto.publico.ProdutoPublicResponse;
import com.mydelivery.dto.publico.RestaurantePublicResponse;
import com.mydelivery.model.BairroEntrega;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.CategoriaRepository;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.PedidoService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
public class PublicController {

    private final RestauranteRepository restauranteRepo;
    private final CategoriaRepository categoriaRepo;
    private final ProdutoRepository produtoRepo;
    private final PedidoService pedidoService;
    private final ConfiguracaoRestauranteRepository configRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.mydelivery.service.EntregaService entregaService;

    @GetMapping("/restaurante/{slug}")
    public ResponseEntity<RestaurantePublicResponse> getRestaurante(@PathVariable String slug) {
        Restaurante r = restauranteRepo.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Restaurante não encontrado"));

        // Public Key do MP é segura de expor (designada como pública pelo MP) —
        // usada pelo SDK no browser pra tokenizar cartão sem expor o access token.
        var cfgPagamento = configRepo.findByRestauranteId(r.getId()).orElse(null);
        String mpPublicKey = cfgPagamento == null ? null : cfgPagamento.getMpPublicKey();

        List<RestaurantePublicResponse.BairroAtendidoPublic> bairros = r.getBairrosAtendidos() == null
                ? List.of()
                : r.getBairrosAtendidos().stream()
                    .filter(b -> b != null && b.getNome() != null && !b.getNome().isBlank())
                    .map(b -> RestaurantePublicResponse.BairroAtendidoPublic.builder()
                            .nome(b.getNome())
                            .taxa(b.getTaxa())
                            .build())
                    .toList();

        List<String> modosPublicos = new java.util.ArrayList<>(r.getModos() == null ? List.of() : r.getModos());
        if (Boolean.TRUE.equals(r.getSomenteRetiradaAtivo())) {
            modosPublicos.removeIf(m -> "delivery".equalsIgnoreCase(m));
            if (!modosPublicos.contains("retirada")) modosPublicos.add("retirada");
        }

        RestaurantePublicResponse response = RestaurantePublicResponse.builder()
                .nome(r.getNome())
                .descricao(r.getDescricao())
                .logoUrl(r.getLogoUrl())
                .capaUrl(r.getCapaUrl())
                .corPrimaria(r.getCorPrimaria())
                .aberto(r.getAberto())
                .tempoEntrega(r.getTempoEntrega())
                .taxaEntrega(r.getTaxaEntrega())  // legado — front novo ignora
                .pedidoMinimo(r.getPedidoMinimo())
                .freteGratisApartirDe(r.getFreteGratisApartirDe())
                .modos(modosPublicos)
                .somenteRetiradaAtivo(Boolean.TRUE.equals(r.getSomenteRetiradaAtivo()))
                .pagamentos(r.getPagamentos())
                // PIX antecipado — só expõe a chave se a flag estiver ativa (evita vazar info sem motivo)
                .exigirPixAntecipado(Boolean.TRUE.equals(r.getExigirPixAntecipado()))
                .chavePixAntecipado(Boolean.TRUE.equals(r.getExigirPixAntecipado()) ? r.getChavePixAntecipado() : null)
                .tipoChavePixAntecipado(Boolean.TRUE.equals(r.getExigirPixAntecipado()) ? r.getTipoChavePixAntecipado() : null)
                .telefone(r.getTelefone())
                .cidade(r.getCidade())
                .estado(r.getEstado())
                .bairrosAtendidos(bairros)
                .mpPublicKey(mpPublicKey)
                .pixOnlineDisponivel(com.mydelivery.service.mercadopago.MercadoPagoDisponibilidade.pix(cfgPagamento))
                .cartaoOnlineDisponivel(com.mydelivery.service.mercadopago.MercadoPagoDisponibilidade.cartao(cfgPagamento))
                .modoTaxa(r.getModoTaxa() == null ? "BAIRRO" : r.getModoTaxa())
                .build();

        // no-cache: cliente final sempre recebe dados atuais (logo, capa, status)
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .body(response);
    }

    @GetMapping("/cardapio/{slug}")
    public ResponseEntity<List<CategoriaPublicResponse>> getCardapio(@PathVariable String slug) {
        Restaurante r = restauranteRepo.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Restaurante não encontrado"));

        List<CategoriaPublicResponse> cardapio = categoriaRepo
                .findByRestauranteAndAtivoTrueOrderByOrdem(r)
                .stream()
                .map(cat -> {
                    // Filtro por dia da semana: se produto tem restrição de dias e
                    // hoje não está na lista, esconde do cliente. Sem restrição =
                    // aparece sempre (retrocompat pra 99% dos produtos).
                    final java.time.ZonedDateTime agora = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/Sao_Paulo"));
                    List<ProdutoPublicResponse> produtos = produtoRepo
                            .findByCategoriaAndDisponivelTrueOrderByOrdem(cat)
                            .stream()
                            .filter(p -> p.disponivelNoHorario(agora))
                            .map(p -> ProdutoPublicResponse.builder()
                            .id(p.getId())
                            .nome(p.getNome())
                            .desc(p.getDescricao())
                            .preco(p.getPreco())
                            .precoOriginal(p.getPrecoOriginal())
                            .precoPix(p.getPrecoPix())
                            .precoDinheiro(p.getPrecoDinheiro())
                            .precoDebito(p.getPrecoDebito())
                            .precoCredito(p.getPrecoCredito())
                            .imgUrl(p.getFotoUrl())
                            .destaque(Boolean.TRUE.equals(p.getDestaque()))
                            .tipo(p.getTipo() != null ? p.getTipo().name() : "NORMAL")
                            .precoVitrine(Boolean.TRUE.equals(p.getPrecoVitrine()))
                            .unidadePreco(p.getUnidadePreco())
                            .precoAPartirDe(Boolean.TRUE.equals(p.getPrecoAPartirDe()))
                            .maisDe18(Boolean.TRUE.equals(p.getMaisDe18()))
                            .build())
                            .toList();

                    return CategoriaPublicResponse.builder()
                            .id(cat.getId())
                            .nome(cat.getNome())
                            .produtos(produtos)
                            .build();
                })
                .toList();

        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .body(cardapio);
    }

    @PostMapping("/pedido")
    public ResponseEntity<Void> novoPedido(@RequestBody NovoPedidoRequest request) {
        pedidoService.criarPedido(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * Cliente cancela um item específico da própria comanda — só funciona se o
     * pedido ainda não foi pra preparo. Validação inclui nome da pessoa, então
     * Maria não consegue cancelar item de João mesmo na mesma mesa.
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/restaurante/{slug}/mesa/{slugMesa}/comanda/{pedidoId}/itens/{itemId}")
    public ResponseEntity<Map<String, Object>> cancelarItemComanda(
            @PathVariable String slug,
            @PathVariable String slugMesa,
            @PathVariable Long pedidoId,
            @PathVariable Long itemId,
            @RequestParam("nome") String nome) {
        pedidoService.cancelarItemComanda(slug, slugMesa, pedidoId, itemId, nome);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Comanda do cliente: lista pedidos ATIVOS da mesa, opcionalmente filtrados
     * pelo nome da pessoa. Usado pelo cardápio mobile pra mostrar "Minha comanda"
     * depois que o cliente envia o primeiro pedido — e atualizar em tempo real
     * conforme o restaurante muda o status (CONFIRMADO → EM_PREPARO → ENTREGUE).
     */
    @GetMapping("/restaurante/{slug}/mesa/{slugMesa}/comanda")
    public ResponseEntity<List<com.mydelivery.dto.pedido.PedidoResponse>> comandaPublica(
            @PathVariable String slug,
            @PathVariable String slugMesa,
            @org.springframework.web.bind.annotation.RequestParam(value = "nome", required = false) String nome) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .body(pedidoService.comandaDaMesa(slug, slugMesa, nome));
    }

    /**
     * Consulta se o restaurante atende um bairro e qual a taxa.
     *
     * Resposta:
     *  - atendido=true  + taxa=Number → restaurante entrega, taxa conhecida
     *  - atendido=true  + taxa=null   → bairro listado, mas taxa não foi configurada
     *  - atendido=false                → fora da área
     *
     * Match é case-insensitive + acento-insensitive — "São José" casa com "sao jose".
     */
    @GetMapping("/restaurante/{slug}/taxa-entrega")
    public ResponseEntity<Map<String, Object>> consultarTaxaPorBairro(
            @PathVariable String slug,
            @RequestParam("bairro") String bairro) {

        Restaurante r = restauranteRepo.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Restaurante não encontrado"));

        if (bairro == null || bairro.isBlank()) {
            return ResponseEntity.ok(Map.of("atendido", false));
        }

        if(com.mydelivery.service.EntregaService.porDistancia(r)) {
            var cotacao=entregaService.consultarTaxaBairro(r,bairro);
            var resp=new java.util.HashMap<String,Object>();
            resp.put("atendido",cotacao.taxa()!=null);resp.put("bairro",cotacao.bairro());resp.put("taxa",cotacao.taxa());
            return ResponseEntity.ok(resp);
        }

        // Normalização avançada: romanos↔árabicos, sem acento, abreviações
        // (sta→santa, dr→doutor, etc). Garante que "Serra Dourada II" do
        // cadastro casa com "serra dourada 2" digitado pelo cliente, e
        // "Capão" casa com "capao".
        BairroEntrega match = com.mydelivery.util.BairroNormalizer.encontrar(r.getBairrosAtendidos(), bairro, r.getCidade());

        if (match == null) {
            return ResponseEntity.ok(Map.of("atendido", false));
        }
        java.util.HashMap<String, Object> resp = new java.util.HashMap<>();
        resp.put("atendido", true);
        resp.put("bairro", match.getNome());
        resp.put("taxa", match.getTaxa()); // pode ser null se não configurada ainda
        return ResponseEntity.ok(resp);
    }

    /** lowercase + remove acentos. Comparação tolerante pra digitação livre. */
    private static String normalizar(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return n.toLowerCase().trim();
    }


}
