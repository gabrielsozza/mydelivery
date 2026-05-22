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

    // ─── CARDÁPIO PÚBLICO ────────────────────────────────────────────────

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
                .fotoUrl(p.getFotoUrl())
                .disponivel(p.getDisponivel())
                .destaque(p.getDestaque())
                .categoriaId(p.getCategoria() != null ? p.getCategoria().getId() : null)
                .categoriaNome(p.getCategoria() != null ? p.getCategoria().getNome() : null)
                .build();
    }
}