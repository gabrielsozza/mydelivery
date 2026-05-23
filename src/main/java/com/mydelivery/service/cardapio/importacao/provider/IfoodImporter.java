package com.mydelivery.service.cardapio.importacao.provider;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * iFood — URL típica: https://www.ifood.com.br/delivery/{cidade}/{slug-do-restaurante}/{merchant-uuid}
 *
 * iFood expõe API pública de catálogo:
 *   GET https://marketplace.ifood.com.br/v1/merchants/{uuid}/catalog
 *
 * O UUID está sempre no final do path. Imagem de produto é URL relativa que
 * vira https://static.ifood-static.com.br/image/upload/.../{caminho}
 *
 * IMPORTANTE: iFood tem proteção anti-bot ativa. Pode falhar com 403 dependendo
 * do IP do servidor — neste caso o orquestrador tenta os providers genéricos.
 */
@Slf4j
@Order(12)
@Component
@RequiredArgsConstructor
public class IfoodImporter implements CardapioImporter {

    private final HtmlFetcher fetcher;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CDN_IMG = "https://static.ifood-static.com.br/image/upload/t_high/pratos/";

    @Override public String getNome() { return "ifood"; }

    @Override
    public boolean suporta(URI url, String html) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
        return host.contains("ifood.com.br");
    }

    @Override
    public ResultadoImport extrair(URI url) throws ImportException {
        String uuid = extrairUuid(url);
        if (uuid == null) throw new ImportException("iFood: UUID do merchant não encontrado na URL.");

        String body;
        try {
            body = fetcher.fetchJson(
                    URI.create("https://marketplace.ifood.com.br/v1/merchants/" + uuid + "/catalog"),
                    Map.of(
                        "Origin", "https://www.ifood.com.br",
                        "Referer", url.toString(),
                        "Accept", "application/json"
                    ));
        } catch (ImportException e) {
            throw new ImportException("iFood bloqueou a chamada (anti-bot). " + e.getMessage());
        }

        JsonNode root;
        try { root = MAPPER.readTree(body); }
        catch (Exception e) { throw new ImportException("iFood: JSON inválido."); }

        // Estrutura: { data: { menu: [ { name, itens: [...] } ] } }
        JsonNode menu = root.path("data").path("menu");
        if (!menu.isArray()) menu = root.path("menu");
        if (!menu.isArray()) throw new ImportException("iFood: campo 'menu' ausente.");

        List<CategoriaImportada> cats = new ArrayList<>();
        for (JsonNode cat : menu) {
            String nome = txt(cat, "name");
            JsonNode itens = cat.path("itens");
            if (!itens.isArray() || itens.size() == 0) itens = cat.path("items");
            List<ProdutoImportado> ps = new ArrayList<>();
            if (itens.isArray()) for (JsonNode it : itens) {
                ProdutoImportado pi = parse(it);
                if (pi != null) ps.add(pi);
            }
            if (!ps.isEmpty()) cats.add(CategoriaImportada.builder()
                    .nome(nome == null ? "Cardápio" : nome).produtos(ps).build());
        }
        if (cats.isEmpty()) throw new ImportException("iFood: catálogo sem produtos.");
        log.info("[iFood] {} categorias / {} produtos", cats.size(),
                cats.stream().mapToInt(c -> c.getProdutos().size()).sum());
        return ResultadoImport.builder().provider(getNome()).categorias(cats).build();
    }

    private String extrairUuid(URI url) {
        // UUID v4 no path: 8-4-4-4-12 hex
        String path = url.getPath();
        if (path == null) return null;
        for (String seg : path.split("/")) {
            if (seg.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                return seg;
            }
        }
        return null;
    }

    private ProdutoImportado parse(JsonNode it) {
        String nome = txt(it, "description", "name");
        if (nome == null) return null;
        BigDecimal preco = preco(it);
        if (preco == null) return null;
        String img = txt(it, "logoUrl");
        if (img == null) img = txt(it, "image");
        if (img != null && !img.startsWith("http")) img = CDN_IMG + img;
        return ProdutoImportado.builder()
                .nome(nome)
                .descricao(txt(it, "details", "longDescription"))
                .preco(preco)
                .imagemUrl(img)
                .build();
    }

    private static String txt(JsonNode n, String... ks) {
        for (String k : ks) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static BigDecimal preco(JsonNode p) {
        for (String k : new String[]{"unitPrice", "minimumPromotionalPrice", "price"}) {
            JsonNode v = p.get(k);
            if (v == null) continue;
            if (v.isNumber()) return new BigDecimal(v.asText());
            if (v.isObject() && v.has("value")) {
                JsonNode vv = v.get("value");
                if (vv.isNumber()) return new BigDecimal(vv.asText());
            }
        }
        return null;
    }
}
