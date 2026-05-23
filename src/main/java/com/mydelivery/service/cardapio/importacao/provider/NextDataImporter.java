package com.mydelivery.service.cardapio.importacao.provider;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
 * Provider genérico para sites Next.js — extrai do <script id="__NEXT_DATA__">.
 *
 * Anota.ai, Goomer, Cardápio Web e muitos outros são Next.js e expõem o estado
 * inicial nesse script. Como cada site tem schema diferente, este provider
 * faz um WALK no JSON procurando arrays de objetos que parecem produtos
 * (têm name + price + image).
 *
 * Heurística:
 *  - Objeto é "produto" se tem (name|nome|title) E (price|preco|valor numérico).
 *  - Objeto é "categoria" se tem (name|nome|title) E array filho de produtos.
 */
@Slf4j
@Order(70)
@Component
@RequiredArgsConstructor
public class NextDataImporter implements CardapioImporter {

    private final HtmlFetcher fetcher;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[] CAMPOS_NOME  = {"name", "nome", "title", "titulo"};
    private static final String[] CAMPOS_PRECO = {"price", "preco", "valor", "amount", "value", "unitPrice"};
    private static final String[] CAMPOS_DESC  = {"description", "descricao", "desc", "details"};
    private static final String[] CAMPOS_IMG   = {"image", "imageUrl", "imagem", "imagemUrl", "photo", "photoUrl", "foto", "fotoUrl", "picture", "thumbnail"};
    private static final String[] CAMPOS_LISTA = {"products", "produtos", "items", "itens", "menuItems", "menus"};

    @Override public String getNome() { return "next-data"; }

    @Override
    public boolean suporta(URI url, String html) {
        return html != null && html.contains("__NEXT_DATA__");
    }

    @Override
    public ResultadoImport extrair(URI url) throws ImportException {
        Document doc = fetcher.fetchHtml(url);
        Element script = doc.selectFirst("script#__NEXT_DATA__");
        if (script == null) throw new ImportException("Sem __NEXT_DATA__ no HTML.");

        JsonNode root;
        try { root = MAPPER.readTree(script.data()); }
        catch (Exception e) { throw new ImportException("__NEXT_DATA__ inválido: " + e.getMessage()); }

        Map<String, List<ProdutoImportado>> bucket = new LinkedHashMap<>();
        walk(root, bucket, "Cardápio");

        if (bucket.isEmpty()) throw new ImportException("Estrutura Next.js não tem produtos detectáveis.");

        List<CategoriaImportada> cats = new ArrayList<>();
        for (Map.Entry<String, List<ProdutoImportado>> e : bucket.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            cats.add(CategoriaImportada.builder().nome(e.getKey()).produtos(e.getValue()).build());
        }
        return ResultadoImport.builder()
                .provider(getNome())
                .categorias(cats)
                .build();
    }

    /**
     * Walker recursivo. Tenta detectar:
     *  - Se o nó atual parece um produto → adiciona ao bucket da categoria
     *  - Se parece uma categoria (tem nome + array filho de produtos) → bumpa categoria
     *  - Senão, desce nos filhos
     */
    private void walk(JsonNode node, Map<String, List<ProdutoImportado>> bucket, String categoria) {
        if (node == null || node.isNull()) return;

        if (node.isObject()) {
            // Detecta categoria com lista de produtos
            String nome = textoEm(node, CAMPOS_NOME);
            for (String campoLista : CAMPOS_LISTA) {
                JsonNode lista = node.get(campoLista);
                if (lista != null && lista.isArray() && lista.size() > 0) {
                    // É categoria? Tem nome próprio?
                    String catNome = (nome != null && nome.length() < 60) ? nome : categoria;
                    for (JsonNode item : lista) walk(item, bucket, catNome);
                }
            }
            // Detecta se ele mesmo é um produto
            if (pareceProduto(node)) {
                ProdutoImportado p = parseProduto(node);
                if (p != null) bucket.computeIfAbsent(categoria, k -> new ArrayList<>()).add(p);
            }
            // Continua descendo em outros campos (objetos aninhados, ex: props.pageProps.menu)
            node.fields().forEachRemaining(entry -> {
                JsonNode child = entry.getValue();
                if (child != null && (child.isObject() || child.isArray())) walk(child, bucket, categoria);
            });
        } else if (node.isArray()) {
            for (JsonNode item : node) walk(item, bucket, categoria);
        }
    }

    private boolean pareceProduto(JsonNode n) {
        boolean temNome = textoEm(n, CAMPOS_NOME) != null;
        boolean temPreco = precoEm(n) != null;
        return temNome && temPreco;
    }

    private ProdutoImportado parseProduto(JsonNode n) {
        return ProdutoImportado.builder()
                .nome(textoEm(n, CAMPOS_NOME))
                .descricao(textoEm(n, CAMPOS_DESC))
                .preco(precoEm(n))
                .imagemUrl(urlImagemEm(n))
                .build();
    }

    private static String textoEm(JsonNode n, String[] campos) {
        for (String c : campos) {
            JsonNode v = n.get(c);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static String urlImagemEm(JsonNode n) {
        for (String c : CAMPOS_IMG) {
            JsonNode v = n.get(c);
            if (v == null) continue;
            if (v.isTextual()) return v.asText();
            if (v.isObject()) {
                JsonNode url = v.get("url");
                if (url != null && url.isTextual()) return url.asText();
            }
            if (v.isArray() && v.size() > 0) {
                JsonNode primeiro = v.get(0);
                if (primeiro != null && primeiro.isTextual()) return primeiro.asText();
                if (primeiro != null && primeiro.isObject()) {
                    JsonNode url = primeiro.get("url");
                    if (url != null && url.isTextual()) return url.asText();
                }
            }
        }
        return null;
    }

    private static BigDecimal precoEm(JsonNode n) {
        for (String c : CAMPOS_PRECO) {
            JsonNode v = n.get(c);
            if (v == null) continue;
            BigDecimal p = parsePreco(v);
            if (p != null && p.signum() > 0) return p;
        }
        return null;
    }

    private static BigDecimal parsePreco(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) {
            BigDecimal b = new BigDecimal(v.asText());
            // Heurística: alguns sites guardam em centavos (ex: 3990 = 39.90)
            // Detecta se valor > 1000 e é múltiplo de inteiro → muito provavelmente centavos.
            if (b.signum() > 0 && b.scale() <= 0 && b.intValue() >= 1000 && b.intValue() < 1_000_000) {
                return b.divide(new BigDecimal(100));
            }
            return b;
        }
        if (v.isTextual()) {
            String s = v.asText().replaceAll("[^0-9,.\\-]", "");
            if (s.isBlank()) return null;
            if (s.contains(",") && s.contains(".")) s = s.replace(".", "").replace(",", ".");
            else s = s.replace(",", ".");
            try { return new BigDecimal(s); } catch (Exception e) { return null; }
        }
        if (v.isObject() && v.has("amount")) return parsePreco(v.get("amount"));
        return null;
    }
}
