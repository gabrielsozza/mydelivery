package com.mydelivery.service.cardapio.importacao.provider;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Extrai produtos a partir de JSON-LD embutido no HTML — schema.org/Menu,
 * MenuSection, MenuItem, Product. Cobre muitos sites de restaurante feitos
 * com WordPress/CMSs que adicionam structured data pro Google.
 *
 * Não específico de nenhuma plataforma — funciona como fallback inteligente.
 */
@Slf4j
@Order(60)
@Component
@RequiredArgsConstructor
public class JsonLdImporter implements CardapioImporter {

    private final HtmlFetcher fetcher;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String getNome() { return "json-ld"; }

    @Override
    public boolean suporta(URI url, String html) {
        return html != null && html.contains("application/ld+json");
    }

    @Override
    public ResultadoImport extrair(URI url) throws ImportException {
        Document doc = fetcher.fetchHtml(url);
        List<JsonNode> blocos = blocosJsonLd(doc);
        if (blocos.isEmpty()) throw new ImportException("Sem JSON-LD na página.");

        Map<String, List<ProdutoImportado>> porCategoria = new LinkedHashMap<>();

        for (JsonNode bloco : blocos) {
            // Pode vir como objeto único ou array
            if (bloco.isArray()) for (JsonNode n : bloco) extrairNode(n, porCategoria, null);
            else extrairNode(bloco, porCategoria, null);
        }

        if (porCategoria.isEmpty()) throw new ImportException("JSON-LD sem itens de cardápio.");

        List<CategoriaImportada> cats = new ArrayList<>();
        for (Map.Entry<String, List<ProdutoImportado>> e : porCategoria.entrySet()) {
            cats.add(CategoriaImportada.builder()
                    .nome(e.getKey())
                    .produtos(e.getValue())
                    .build());
        }

        return ResultadoImport.builder()
                .provider(getNome())
                .categorias(cats)
                .build();
    }

    /** Lê todos os blocos <script type="application/ld+json"> e parseia cada um. */
    private List<JsonNode> blocosJsonLd(Document doc) {
        List<JsonNode> out = new ArrayList<>();
        for (Element s : doc.select("script[type=application/ld+json]")) {
            String txt = s.data();
            if (txt == null || txt.isBlank()) continue;
            try { out.add(MAPPER.readTree(txt)); }
            catch (Exception ex) { log.debug("[JsonLd] bloco inválido ignorado: {}", ex.getMessage()); }
        }
        return out;
    }

    /**
     * Walker recursivo no JSON-LD procurando MenuItem/Product.
     * Schema.org/Menu tem hierarquia: Menu → hasMenuSection[] → hasMenuItem[].
     */
    private void extrairNode(JsonNode node, Map<String, List<ProdutoImportado>> bucket, String categoriaAtual) {
        if (node == null || !node.isObject()) return;
        String tipo = textoDe(node.get("@type")); // pode ser string ou array — pegamos primeiro
        if (tipo == null) tipo = "";

        if (tipo.contains("MenuSection")) {
            String nome = textoDe(node.get("name"));
            if (nome == null) nome = categoriaAtual;
            // Pode ter "hasMenuItem" ou "hasMenuSection" filhos
            walkFilhos(node.get("hasMenuItem"), bucket, nome);
            walkFilhos(node.get("hasMenuSection"), bucket, nome);
            return;
        }
        if (tipo.contains("Menu")) {
            walkFilhos(node.get("hasMenuSection"), bucket, categoriaAtual);
            walkFilhos(node.get("hasMenuItem"), bucket, categoriaAtual);
            return;
        }
        if (tipo.contains("MenuItem") || tipo.contains("Product")) {
            ProdutoImportado p = parseItem(node);
            if (p != null) {
                bucket.computeIfAbsent(categoriaAtual == null ? "Cardápio" : categoriaAtual, k -> new ArrayList<>())
                        .add(p);
            }
            return;
        }
        // Tipo genérico — varre campos comuns que costumam ter aninhamento
        walkFilhos(node.get("hasMenuSection"), bucket, categoriaAtual);
        walkFilhos(node.get("hasMenuItem"), bucket, categoriaAtual);
        walkFilhos(node.get("itemListElement"), bucket, categoriaAtual);
    }

    private void walkFilhos(JsonNode filhos, Map<String, List<ProdutoImportado>> bucket, String cat) {
        if (filhos == null) return;
        if (filhos.isArray()) for (JsonNode f : filhos) extrairNode(f, bucket, cat);
        else extrairNode(filhos, bucket, cat);
    }

    private ProdutoImportado parseItem(JsonNode n) {
        String nome = textoDe(n.get("name"));
        if (nome == null) return null;
        String desc = textoDe(n.get("description"));
        String img = textoDe(n.get("image"));
        if (img == null && n.has("image") && n.get("image").isObject()) {
            img = textoDe(n.get("image").get("url"));
        }
        BigDecimal preco = lerPreco(n);

        return ProdutoImportado.builder()
                .nome(nome)
                .descricao(desc)
                .preco(preco)
                .imagemUrl(img)
                .build();
    }

    /** Schema.org/Offer.price ou MenuItem.offers.price. Aceita string ("R$ 12,50") ou número. */
    private BigDecimal lerPreco(JsonNode item) {
        // 1) MenuItem.offers (objeto único ou array)
        JsonNode offers = item.get("offers");
        if (offers != null) {
            if (offers.isArray() && offers.size() > 0) offers = offers.get(0);
            if (offers != null && offers.isObject()) {
                BigDecimal p = parsePreco(offers.get("price"));
                if (p != null) return p;
                p = parsePreco(offers.get("lowPrice"));
                if (p != null) return p;
            }
        }
        // 2) price no próprio item
        return parsePreco(item.get("price"));
    }

    private BigDecimal parsePreco(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isNumber()) return new BigDecimal(n.asText());
        if (n.isTextual()) {
            String s = n.asText().replaceAll("[^0-9,.-]", "");
            if (s.isBlank()) return null;
            // BR usa vírgula; troca pra ponto. Se tiver ponto E vírgula, ponto é milhar.
            if (s.contains(",") && s.contains(".")) s = s.replace(".", "").replace(",", ".");
            else s = s.replace(",", ".");
            try { return new BigDecimal(s); } catch (Exception ex) { return null; }
        }
        return null;
    }

    private static String textoDe(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isArray() && n.size() > 0) return textoDe(n.get(0));
        if (n.isTextual()) return n.asText();
        if (n.isObject() && n.has("@value")) return textoDe(n.get("@value"));
        return null;
    }
}
