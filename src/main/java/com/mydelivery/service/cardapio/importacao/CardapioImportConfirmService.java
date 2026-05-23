package com.mydelivery.service.cardapio.importacao;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.Categoria;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.CategoriaRepository;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.service.CloudinaryService;
import com.mydelivery.service.cardapio.importacao.dto.CategoriaImportada;
import com.mydelivery.service.cardapio.importacao.dto.ProdutoImportado;
import com.mydelivery.service.cardapio.importacao.dto.ResultadoImport;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Recebe o ResultadoImport (possivelmente editado pelo usuário no preview) e
 * persiste no banco: cria/reusa categorias, cria produtos. Faz upload das
 * imagens pro Cloudinary baixando da URL origem — se falhar, persiste sem
 * imagem (não bloqueia o resto).
 *
 * Lógica deliberadamente conservadora:
 *  - Reusa categoria existente se o nome (case-insensitive) bater
 *  - NÃO atualiza produto existente; sempre cria novo (evita perder dados)
 *  - Limite hard de 200 produtos por importação (anti-abuso)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardapioImportConfirmService {

    private final CategoriaRepository categoriaRepo;
    private final ProdutoRepository produtoRepo;
    private final CloudinaryService cloudinaryService;
    private final HtmlFetcher fetcher;

    private static final int LIMITE_PRODUTOS = 200;

    @Transactional
    public ResultadoConfirm confirmar(Restaurante restaurante, ResultadoImport edicaoCliente) {
        if (edicaoCliente == null || edicaoCliente.getCategorias() == null
                || edicaoCliente.getCategorias().isEmpty()) {
            throw new RuntimeException("Nenhuma categoria selecionada pra importar.");
        }

        int total = edicaoCliente.getTotalProdutos();
        if (total == 0) throw new RuntimeException("Nenhum produto selecionado.");
        if (total > LIMITE_PRODUTOS) {
            throw new RuntimeException("Importação muito grande (>" + LIMITE_PRODUTOS
                    + " produtos). Reduza a seleção.");
        }

        // Cache: nome categoria → entidade. Evita lookup repetido + reusa categorias existentes.
        Map<String, Categoria> categoriasCache = new HashMap<>();
        for (Categoria c : categoriaRepo.findByRestauranteIdOrderByOrdemAsc(restaurante.getId())) {
            if (c.getNome() != null) categoriasCache.put(c.getNome().toLowerCase().trim(), c);
        }

        int criados = 0, categoriasCriadas = 0, imagensOk = 0, imagensFalha = 0;
        int ordemProduto = 0;

        for (CategoriaImportada cat : edicaoCliente.getCategorias()) {
            if (cat.getProdutos() == null || cat.getProdutos().isEmpty()) continue;
            String chave = cat.getNome() == null ? "cardápio" : cat.getNome().toLowerCase().trim();
            Categoria entidadeCat = categoriasCache.get(chave);
            if (entidadeCat == null) {
                entidadeCat = Categoria.builder()
                        .restaurante(restaurante)
                        .nome(cat.getNome() == null ? "Cardápio" : cat.getNome())
                        .ordem(categoriasCache.size())
                        .ativo(true)
                        .build();
                entidadeCat = categoriaRepo.save(entidadeCat);
                categoriasCache.put(chave, entidadeCat);
                categoriasCriadas++;
            }

            for (ProdutoImportado p : cat.getProdutos()) {
                String fotoUrl = null;
                if (p.getImagemUrl() != null && !p.getImagemUrl().isBlank()) {
                    fotoUrl = baixarEUploadar(p.getImagemUrl());
                    if (fotoUrl != null) imagensOk++;
                    else { imagensFalha++; fotoUrl = p.getImagemUrl(); /* fallback: URL origem */ }
                }
                Produto produto = Produto.builder()
                        .restaurante(restaurante)
                        .categoria(entidadeCat)
                        .nome(p.getNome())
                        .descricao(p.getDescricao())
                        .preco(p.getPreco())
                        .fotoUrl(fotoUrl)
                        .disponivel(true)
                        .destaque(false)
                        .ordem(ordemProduto++)
                        .build();
                produtoRepo.save(produto);
                criados++;
            }
        }

        log.info("[ImportConfirm] restaurante={} criados={} categorias-novas={} imgs-ok={} imgs-falha={}",
                restaurante.getId(), criados, categoriasCriadas, imagensOk, imagensFalha);

        return new ResultadoConfirm(criados, categoriasCriadas, imagensOk, imagensFalha);
    }

    /** Baixa a imagem da URL e re-upload pro Cloudinary. Null se qualquer etapa falhar. */
    private String baixarEUploadar(String url) {
        try {
            byte[] bytes = fetcher.fetchBytes(URI.create(url));
            if (bytes == null || bytes.length == 0) return null;
            return cloudinaryService.uploadBytes(bytes, "import");
        } catch (Exception e) {
            log.debug("[ImportConfirm] imagem falhou ({}): {}", url, e.getMessage());
            return null;
        }
    }

    /** DTO leve de resposta do confirm. */
    public record ResultadoConfirm(int produtosCriados, int categoriasCriadas, int imagensOk, int imagensFalha) {}
}
