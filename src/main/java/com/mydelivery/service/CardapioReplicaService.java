package com.mydelivery.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.Banner;
import com.mydelivery.model.Categoria;
import com.mydelivery.model.ComplementoGrupo;
import com.mydelivery.model.ComplementoItem;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.BannerRepository;
import com.mydelivery.repository.CategoriaRepository;
import com.mydelivery.repository.ComplementoGrupoRepository;
import com.mydelivery.repository.ComplementoItemRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Replica o cardápio completo de uma loja pra outra (uso admin/suporte).
 *
 * O que é copiado:
 *  - Categorias (nome, ordem, ativo)
 *  - Produtos (nome, descrição, preço, foto, disponível, destaque, ordem)
 *  - Grupos de complementos + itens (preço adicional, min/max escolhas, ativo)
 *  - Banners promocionais (com vínculo ao produto remapeado)
 *
 * O que NÃO é copiado (proposital):
 *  - Ficha técnica / insumos (custos são operação interna por loja)
 *  - Pedidos / histórico / clientes / fidelidade
 *
 * Imagens: reusa a mesma URL Cloudinary (CDN público, não precisa re-upload).
 *
 * Modos:
 *  - ACRESCENTAR: mantém categorias/produtos do destino e adiciona os novos
 *  - SUBSTITUIR:  apaga TUDO do destino antes (usa CardapioService.prepararProdutoParaExclusao)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardapioReplicaService {

    private final RestauranteRepository restauranteRepo;
    private final CategoriaRepository categoriaRepo;
    private final ProdutoRepository produtoRepo;
    private final ComplementoGrupoRepository grupoRepo;
    private final ComplementoItemRepository itemRepo;
    private final BannerRepository bannerRepo;
    private final CardapioService cardapioService;

    /** Resultado da replicação pra uma loja destino. */
    public record Resultado(
        Long destinoId, String destinoNome,
        int categorias, int produtos, int grupos, int itens, int banners,
        String modo, String erro
    ) {}

    @Transactional
    public Resultado replicar(Long origemId, Long destinoId, String modo) {
        if (origemId == null || destinoId == null) {
            throw new IllegalArgumentException("origem/destino obrigatórios");
        }
        if (origemId.equals(destinoId)) {
            throw new IllegalArgumentException("Origem não pode ser igual ao destino");
        }
        Restaurante origem = restauranteRepo.findById(origemId)
            .orElseThrow(() -> new IllegalArgumentException("Loja origem não encontrada"));
        Restaurante destino = restauranteRepo.findById(destinoId)
            .orElseThrow(() -> new IllegalArgumentException("Loja destino não encontrada"));

        boolean substituir = "SUBSTITUIR".equalsIgnoreCase(modo);
        String modoFinal = substituir ? "SUBSTITUIR" : "ACRESCENTAR";

        // 1) Se SUBSTITUIR: apaga tudo do destino primeiro
        if (substituir) {
            limparCardapioDestino(destino);
        }

        // 2) Carrega tudo da origem
        List<Categoria> catsOrigem = categoriaRepo.findByRestauranteIdOrderByOrdemAsc(origemId);

        Map<Long, Long> mapaCategorias = new HashMap<>();
        Map<Long, Long> mapaProdutos   = new HashMap<>();
        int countCat = 0, countProd = 0, countGrp = 0, countItem = 0, countBan = 0;

        // 3) Categorias
        for (Categoria c : catsOrigem) {
            Categoria nova = new Categoria();
            nova.setRestaurante(destino);
            nova.setNome(c.getNome());
            nova.setOrdem(c.getOrdem() != null ? c.getOrdem() : 0);
            nova.setAtivo(Boolean.TRUE.equals(c.getAtivo()));
            categoriaRepo.save(nova);
            mapaCategorias.put(c.getId(), nova.getId());
            countCat++;
        }

        // 4) Produtos (por categoria) + complementos + itens
        for (Categoria cOrig : catsOrigem) {
            Long novaCatId = mapaCategorias.get(cOrig.getId());
            Categoria novaCat = categoriaRepo.findById(novaCatId).orElseThrow();
            List<Produto> prods = produtoRepo.findByCategoriaId(cOrig.getId());

            for (Produto p : prods) {
                Produto np = new Produto();
                np.setRestaurante(destino);
                np.setCategoria(novaCat);
                np.setNome(p.getNome());
                np.setDescricao(p.getDescricao());
                np.setPreco(p.getPreco());
                np.setPrecoOriginal(p.getPrecoOriginal());
                np.setFotoUrl(p.getFotoUrl()); // CDN — reusa
                np.setDisponivel(Boolean.TRUE.equals(p.getDisponivel()));
                np.setDestaque(Boolean.TRUE.equals(p.getDestaque()));
                np.setOrdem(p.getOrdem() != null ? p.getOrdem() : 0);
                produtoRepo.save(np);
                mapaProdutos.put(p.getId(), np.getId());
                countProd++;

                // Grupos de complementos do produto
                List<ComplementoGrupo> grupos = grupoRepo.findByProdutoIdOrderByIdAsc(p.getId());
                for (ComplementoGrupo g : grupos) {
                    ComplementoGrupo ng = new ComplementoGrupo();
                    ng.setProduto(np);
                    ng.setNome(g.getNome());
                    ng.setObrigatorio(Boolean.TRUE.equals(g.getObrigatorio()));
                    ng.setMinEscolhas(g.getMinEscolhas() != null ? g.getMinEscolhas() : 0);
                    ng.setMaxEscolhas(g.getMaxEscolhas() != null ? g.getMaxEscolhas() : 1);
                    grupoRepo.save(ng);
                    countGrp++;

                    // Itens do grupo
                    List<ComplementoItem> itens = itemRepo.findByGrupoId(g.getId());
                    for (ComplementoItem it : itens) {
                        ComplementoItem ni = new ComplementoItem();
                        ni.setGrupo(ng);
                        ni.setNome(it.getNome());
                        ni.setPrecoAdicional(it.getPrecoAdicional());
                        ni.setAtivo(Boolean.TRUE.equals(it.getAtivo()));
                        itemRepo.save(ni);
                        countItem++;
                    }
                }
            }
        }

        // 5) Banners promocionais (remapeia produto_id se vinculado)
        List<Banner> banners = bannerRepo.findByRestauranteIdOrderByOrdemAsc(origemId);
        for (Banner b : banners) {
            Banner nb = Banner.builder()
                .restaurante(destino)
                .imagemUrl(b.getImagemUrl())
                .ordem(b.getOrdem() != null ? b.getOrdem() : 0)
                .ativo(Boolean.TRUE.equals(b.getAtivo()))
                .build();
            if (b.getProduto() != null) {
                Long novoProdId = mapaProdutos.get(b.getProduto().getId());
                if (novoProdId != null) {
                    Produto np = produtoRepo.findById(novoProdId).orElse(null);
                    nb.setProduto(np);
                }
            }
            bannerRepo.save(nb);
            countBan++;
        }

        log.info("[Replica] origem={} destino={} modo={} cat={} prod={} grp={} item={} ban={}",
            origemId, destinoId, modoFinal, countCat, countProd, countGrp, countItem, countBan);

        return new Resultado(destinoId, destino.getNome(),
            countCat, countProd, countGrp, countItem, countBan, modoFinal, null);
    }

    /** Apaga categorias/produtos/complementos/banners do destino. */
    private void limparCardapioDestino(Restaurante destino) {
        // Banners primeiro (referenciam produtos)
        bannerRepo.deleteAll(bannerRepo.findByRestauranteIdOrderByOrdemAsc(destino.getId()));

        // Produtos: usa o cleanup que já existe (ficha técnica, pedidoItens, banners)
        var cats = categoriaRepo.findByRestauranteIdOrderByOrdemAsc(destino.getId());
        for (Categoria c : cats) {
            var prods = produtoRepo.findByCategoriaId(c.getId());
            for (Produto p : prods) {
                cardapioService.prepararProdutoParaExclusao(p);
                // Apaga complementos
                var grupos = grupoRepo.findByProdutoIdOrderByIdAsc(p.getId());
                for (ComplementoGrupo g : grupos) {
                    itemRepo.deleteAll(itemRepo.findByGrupoId(g.getId()));
                }
                grupoRepo.deleteAll(grupos);
            }
            produtoRepo.deleteAll(prods);
        }
        categoriaRepo.deleteAll(cats);
    }
}
