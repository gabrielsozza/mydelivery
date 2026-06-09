package com.mydelivery.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.cardapio.CategoriaComProdutosResponse;
import com.mydelivery.dto.cardapio.CategoriaRequest;
import com.mydelivery.dto.cardapio.ProdutoRequest;
import com.mydelivery.dto.cardapio.ProdutoResponse;
import com.mydelivery.model.Categoria;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.CategoriaRepository;
import com.mydelivery.repository.FichaTecnicaItemRepository;
import com.mydelivery.repository.PedidoItemRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CardapioService {

    private final CategoriaRepository categoriaRepository;
    private final ProdutoRepository produtoRepository;
    private final RestauranteRepository restauranteRepository;
    private final FichaTecnicaItemRepository fichaTecnicaItemRepository;
    private final PedidoItemRepository pedidoItemRepository;
    private final com.mydelivery.repository.BannerRepository bannerRepository;
    private final com.mydelivery.repository.ComplementoGrupoRepository complementoGrupoRepository;

    // ─── CARDÁPIO PÚBLICO ────────────────────────────────────────────────

    /**
     * Cardapio publico do cliente final. Cacheado por 60s — endpoint hot
     * (cada cliente que abre o link puxa). Cache invalida sozinho em 60s,
     * entao alteracao do restaurante aparece pro cliente em ate 1 minuto.
     * Reducao de queries no banco: ~95% em horario de pico.
     */
    @org.springframework.cache.annotation.Cacheable(value = "cardapio", key = "#slug")
    public List<CategoriaComProdutosResponse> getCardapioPublico(String slug) {
        Restaurante restaurante = restauranteRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));

        List<Categoria> categorias = categoriaRepository
                .findByRestauranteIdAndAtivoTrueOrderByOrdemAsc(restaurante.getId());

        return categorias.stream().map(cat -> {
            List<ProdutoResponse> produtos = produtoRepository
                    .findByRestauranteIdAndDisponivelTrue(restaurante.getId())
                    .stream()
                    .filter(p -> p.getCategoria() != null &&
                                 p.getCategoria().getId().equals(cat.getId()))
                    // Ordena por ordem (nulls last) — respeita reordenação do painel.
                    // Tie-breaker pelo id pra ordem estável quando vários têm ordem=0.
                    .sorted(java.util.Comparator
                            .comparing((Produto p) -> p.getOrdem() == null ? Integer.MAX_VALUE : p.getOrdem())
                            .thenComparing(Produto::getId))
                    .map(this::toProdutoResponse)
                    .toList();

            return CategoriaComProdutosResponse.builder()
                    .id(cat.getId())
                    .nome(cat.getNome())
                    .ordem(cat.getOrdem())
                    .produtos(produtos)
                    .build();
        }).toList();
    }

    // ─── CATEGORIAS ──────────────────────────────────────────────────────

    public List<Categoria> getCategorias(Long restauranteId) {
        return categoriaRepository.findByRestauranteIdOrderByOrdemAsc(restauranteId);
    }

    @Transactional
    public Categoria criarCategoria(Long restauranteId, CategoriaRequest request) {
        Restaurante restaurante = restauranteRepository.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));

        Categoria categoria = new Categoria();
        categoria.setRestaurante(restaurante);
        categoria.setNome(request.getNome());
        categoria.setOrdem(request.getOrdem());
        categoria.setAtivo(request.getAtivo());

        return categoriaRepository.save(categoria);
    }

    @Transactional
    public Categoria atualizarCategoria(Long restauranteId, Long categoriaId,
                                         CategoriaRequest request) {
        Categoria categoria = categoriaRepository.findById(categoriaId)
                .orElseThrow(() -> new RuntimeException("Categoria não encontrada"));

        validarPropriedade(categoria.getRestaurante().getId(), restauranteId);

        categoria.setNome(request.getNome());
        categoria.setOrdem(request.getOrdem());
        categoria.setAtivo(request.getAtivo());

        return categoriaRepository.save(categoria);
    }

    @Transactional
    public void deletarCategoria(Long restauranteId, Long categoriaId) {
        Categoria categoria = categoriaRepository.findById(categoriaId)
                .orElseThrow(() -> new RuntimeException("Categoria não encontrada"));

        validarPropriedade(categoria.getRestaurante().getId(), restauranteId);

        // Cada produto pode ter FKs em FichaTecnicaItem (ficha técnica) e PedidoItem
        // (histórico de pedidos). Precisamos preparar cada um antes de deletar.
        var produtosDaCategoria = produtoRepository.findByCategoriaId(categoriaId);
        for (Produto p : produtosDaCategoria) {
            prepararProdutoParaExclusao(p);
        }
        if (!produtosDaCategoria.isEmpty()) {
            produtoRepository.deleteAll(produtosDaCategoria);
        }
        categoriaRepository.delete(categoria);
    }

    /**
     * Prepara um produto pra ser deletado em segurança:
     *  1. Apaga as fichas técnicas que apontam pra ele (FK NOT NULL na ficha)
     *  2. Em cada PedidoItem que aponta pra ele:
     *     - Garante que o snapshot `nomeProduto` está preenchido
     *     - Desvincula (produto = null) — assim o histórico do pedido sobrevive
     *
     * Compartilhado entre exclusão manual de categoria e modo "substituir" da importação.
     */
    @Transactional
    public void prepararProdutoParaExclusao(Produto produto) {
        if (produto == null) return;
        // 1) Fichas técnicas
        var fichas = fichaTecnicaItemRepository.findByProdutoId(produto.getId());
        if (!fichas.isEmpty()) {
            fichaTecnicaItemRepository.deleteAll(fichas);
        }
        // 2) PedidoItens antigos — preserva o nome em snapshot
        var pedidoItens = pedidoItemRepository.findByProdutoId(produto.getId());
        if (!pedidoItens.isEmpty()) {
            for (var pi : pedidoItens) {
                if (pi.getNomeProduto() == null || pi.getNomeProduto().isBlank()) {
                    pi.setNomeProduto(produto.getNome());
                }
                pi.setProduto(null); // libera a FK
            }
            pedidoItemRepository.saveAll(pedidoItens);
        }
        // 3) Banners promocionais — desvincula produto (banner continua existindo sem destino)
        bannerRepository.desvincularProduto(produto.getId());
        // 4) Grupos de complementos — apaga (FK obriga a limpar antes de deletar produto).
        //    cascade=ALL + orphanRemoval=true em ComplementoGrupo.itens arrasta os itens.
        var grupos = complementoGrupoRepository.findByProdutoIdOrderByIdAsc(produto.getId());
        if (!grupos.isEmpty()) complementoGrupoRepository.deleteAll(grupos);
    }

    // ─── PRODUTOS ────────────────────────────────────────────────────────

    public List<ProdutoResponse> getProdutos(Long restauranteId) {
        return produtoRepository.findByRestauranteId(restauranteId)
                .stream().map(this::toProdutoResponse).toList();
    }

    // ← MÉTODO NOVO
    public List<ProdutoResponse> getProdutosPorCategoria(Long restauranteId, Long categoriaId) {
        return produtoRepository
                .findByCategoriaIdAndRestauranteIdOrderByOrdem(categoriaId, restauranteId)
                .stream().map(this::toProdutoResponse).toList();
    }

    @Transactional
    public ProdutoResponse criarProduto(Long restauranteId, ProdutoRequest request) {
        Restaurante restaurante = restauranteRepository.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));

        Produto produto = new Produto();
        produto.setRestaurante(restaurante);
        produto.setNome(request.getNome());
        produto.setDescricao(request.getDescricao());
        produto.setPreco(request.getPreco());
        // precoOriginal só faz sentido se for MAIOR que preco (promo válida).
        // Front pode mandar 0/null pra "sem promo".
        produto.setPrecoOriginal(promoValida(request.getPrecoOriginal(), request.getPreco())
                ? request.getPrecoOriginal() : null);
        produto.setFotoUrl(request.getFotoUrl());
        produto.setDisponivel(request.getDisponivel());
        produto.setDestaque(request.getDestaque());

        if (request.getCategoriaId() != null) {
            Categoria cat = categoriaRepository.findById(request.getCategoriaId())
                    .orElseThrow(() -> new RuntimeException("Categoria não encontrada"));
            produto.setCategoria(cat);
        }

        return toProdutoResponse(produtoRepository.save(produto));
    }

    @Transactional
    public ProdutoResponse atualizarProduto(Long restauranteId, Long produtoId,
                                             ProdutoRequest request) {
        Produto produto = produtoRepository.findById(produtoId)
                .orElseThrow(() -> new RuntimeException("Produto não encontrado"));

        validarPropriedade(produto.getRestaurante().getId(), restauranteId);

        // Update parcial-friendly: só sobrescreve quando o campo foi enviado.
        // Antes setFotoUrl(null) zerava a foto se o front PUT-asse sem incluí-la
        // (ex: ao editar só nome/preço) — isso fazia a foto SUMIR após salvar.
        if (request.getNome() != null)         produto.setNome(request.getNome());
        if (request.getDescricao() != null)    produto.setDescricao(request.getDescricao());
        if (request.getPreco() != null)        produto.setPreco(request.getPreco());
        // precoOriginal: 0/null = remove promo. Vem promo válida (>preco)? grava.
        if (request.getPrecoOriginal() != null) {
            java.math.BigDecimal po = request.getPrecoOriginal();
            java.math.BigDecimal pp = request.getPreco() != null ? request.getPreco() : produto.getPreco();
            produto.setPrecoOriginal(promoValida(po, pp) ? po : null);
        }
        if (request.getFotoUrl() != null)      produto.setFotoUrl(request.getFotoUrl());
        if (request.getDisponivel() != null)   produto.setDisponivel(request.getDisponivel());
        if (request.getDestaque() != null)     produto.setDestaque(request.getDestaque());

        if (request.getCategoriaId() != null) {
            Categoria cat = categoriaRepository.findById(request.getCategoriaId())
                    .orElseThrow(() -> new RuntimeException("Categoria não encontrada"));
            produto.setCategoria(cat);
        }

        return toProdutoResponse(produtoRepository.save(produto));
    }

    @Transactional
    public void deletarProduto(Long restauranteId, Long produtoId) {
        Produto produto = produtoRepository.findById(produtoId)
                .orElseThrow(() -> new RuntimeException("Produto não encontrado"));

        validarPropriedade(produto.getRestaurante().getId(), restauranteId);
        // Mesmo cleanup da exclusão de categoria — preserva histórico
        prepararProdutoParaExclusao(produto);
        produtoRepository.delete(produto);
    }

    /**
     * Toggle de disponibilidade do produto. Operação leve — só muda o campo
     * disponivel sem precisar revalidar nome/preco/categoria como o PUT
     * completo faria. Multi-tenant safe.
     */
    @Transactional
    public ProdutoResponse atualizarDisponibilidade(Long restauranteId, Long produtoId, boolean disponivel) {
        Produto produto = produtoRepository.findById(produtoId)
                .orElseThrow(() -> new RuntimeException("Produto não encontrado"));
        validarPropriedade(produto.getRestaurante().getId(), restauranteId);
        produto.setDisponivel(disponivel);
        return toProdutoResponse(produtoRepository.save(produto));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private void validarPropriedade(Long dono, Long solicitante) {
        if (!dono.equals(solicitante)) {
            throw new RuntimeException("Acesso negado a este recurso");
        }
    }

    private ProdutoResponse toProdutoResponse(Produto p) {
        return ProdutoResponse.builder()
                .id(p.getId())
                .nome(p.getNome())
                .descricao(p.getDescricao())
                .preco(p.getPreco())
                .precoOriginal(p.getPrecoOriginal())
                .fotoUrl(p.getFotoUrl())
                .disponivel(p.getDisponivel())
                .destaque(p.getDestaque())
                .categoriaId(p.getCategoria() != null ? p.getCategoria().getId() : null)
                .categoriaNome(p.getCategoria() != null ? p.getCategoria().getNome() : null)
                .ordem(p.getOrdem())
                .build();
    }

    /** Promo válida = original > preco (e ambos > 0). Evita guardar lixo no banco. */
    private static boolean promoValida(java.math.BigDecimal precoOriginal, java.math.BigDecimal preco) {
        if (precoOriginal == null || preco == null) return false;
        return precoOriginal.compareTo(java.math.BigDecimal.ZERO) > 0
            && precoOriginal.compareTo(preco) > 0;
    }

    /** Reordena os produtos da categoria conforme a lista de IDs. Multi-tenant safe. */
    @Transactional
    public void reordenarProdutosNaCategoria(Long restauranteId, Long categoriaId, java.util.List<Long> idsNaOrdem) {
        if (idsNaOrdem == null || idsNaOrdem.isEmpty()) return;
        var existentes = produtoRepository.findByCategoriaIdAndRestauranteIdOrderByOrdem(categoriaId, restauranteId);
        var porId = new java.util.HashMap<Long, Produto>();
        for (var p : existentes) porId.put(p.getId(), p);
        int ord = 0;
        for (Long id : idsNaOrdem) {
            var p = porId.get(id);
            if (p == null) continue; // ignora ids estranhos (multi-tenant safe)
            p.setOrdem(ord++);
        }
        produtoRepository.saveAll(porId.values());
    }
}