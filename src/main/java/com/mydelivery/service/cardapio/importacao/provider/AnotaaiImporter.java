package com.mydelivery.service.cardapio.importacao.provider;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mydelivery.service.cardapio.importacao.HtmlFetcher;
import com.mydelivery.service.cardapio.importacao.ImportException;
import com.mydelivery.service.cardapio.importacao.dto.CategoriaImportada;
import com.mydelivery.service.cardapio.importacao.dto.ProdutoImportado;
import com.mydelivery.service.cardapio.importacao.dto.ResultadoImport;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Anota.ai (pedidos.anota.ai/cardapio/{slug} ou {slug}.anota.ai) — usa Next.js,
 * estado completo do cardápio fica embutido no __NEXT_DATA__.
 *
 * Como esses sites são bem padronizados, este provider tem prioridade ALTA
 * sobre o NextDataImporter genérico — ele sabe exatamente onde achar
 * categorias e produtos no schema deles.
 */
@Slf4j
@Order(10)
@Component
@RequiredArgsConstructor
public class AnotaaiImporter implements CardapioImporter {

    private final HtmlFetcher fetcher;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String getNome() { return "anotaai"; }

    @Override
    public boolean suporta(URI url, String html) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
        return host.contains("anota.ai") || host.contains("anotaai")
            || (html != null && html.contains("anota.ai"));
    }

    @Override
    public ResultadoImport extrair(URI url) throws ImportException {
        var doc = fetcher.fetchHtml(url);
        var script = doc.selectFirst("script#__NEXT_DATA__");
        if (script == null) throw new ImportException("Anota.ai sem __NEXT_DATA__.");
        JsonNode root;
        try { root = MAPPER.readTree(script.data()); }
        catch (Exception e) { throw new ImportException("__NEXT_DATA__ inválido: " + e.getMessage()); }

        // Caminho típico: props.pageProps.store.{menu|categories|products}
        JsonNode pageProps = root.path("props").path("pageProps");
        List<CategoriaImportada> cats = new ArrayList<>();

        // Tentativa 1: categories[] com products[] aninhado
        JsonNode categories = pageProps.path("store").path("categories");
        if (!categories.isArray() || categories.size() == 0) categories = pageProps.path("categories");
        if (!categories.isArray() || categories.size() == 0) categories = pageProps.path("menu").path("categories");

        if (categories.isArray() && categories.size() > 0) {
            for (JsonNode cat : categories) {
                String nome = txt(cat, "name", "title");
                List<ProdutoImportado> prods = new ArrayList<>();
                JsonNode prodList = cat.path("products");
                if (!prodList.isArray() || prodList.size() == 0) prodList = cat.path("items");
                if (prodList.isArray()) for (JsonNode p : prodList) {
                    ProdutoImportado pi = parseProduto(p);
                    if (pi != null) prods.add(pi);
                }
                if (!prods.isEmpty()) cats.add(CategoriaImportada.builder()
                        .nome(nome == null ? "Cardápio" : nome).produtos(prods).build());
            }
        }

        if (cats.isEmpty()) throw new ImportException("Anota.ai: schema esperado não encontrado.");
        log.info("[AnotaAi] extraídas {} categorias", cats.size());
        return ResultadoImport.builder().provider(getNome()).categorias(cats).build();
    }

    private ProdutoImportado parseProduto(JsonNode p) {
        String nome = txt(p, "name", "title");
        if (nome == null) return null;
        BigDecimal preco = preco(p);
        if (preco == null) return null;
        return ProdutoImportado.builder()
                .nome(nome)
                .descricao(txt(p, "description", "details"))
                .preco(preco)
                .imagemUrl(img(p))
                .build();
    }

    private static String txt(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static BigDecimal preco(JsonNode p) {
        for (String k : new String[]{"price", "value", "unitPrice", "actualPrice"}) {
            JsonNode v = p.get(k);
            if (v == null || v.isNull()) continue;
            if (v.isNumber()) return new BigDecimal(v.asText());
            if (v.isTextual()) {
                String s = v.asText().replaceAll("[^0-9,.\\-]", "");
                if (s.contains(",") && s.contains(".")) s = s.replace(".", "").replace(",", ".");
                else s = s.replace(",", ".");
                try { return new BigDecimal(s); } catch (Exception ignore) {}
            }
        }
        return null;
    }

    private static String img(JsonNode p) {
        for (String k : new String[]{"image", "imageUrl", "photo", "picture", "thumbnail"}) {
            JsonNode v = p.get(k);
            if (v == null) continue;
            if (v.isTextual()) return v.asText();
            if (v.isObject() && v.has("url")) return v.get("url").asText();
        }
        return null;
    }
}
