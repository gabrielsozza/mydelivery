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
 * OlaClick — links tipo olaclick.app/{slug} ou {slug}.olaclick.app.
 *
 * OlaClick é um SPA mas tem API REST pública: api.olaclick.app/api/v1/companies/{slug}/menu
 * Vamos tentar essa API direto — bem mais rápido e robusto que parsear o SPA.
 */
@Slf4j
@Order(11)
@Component
@RequiredArgsConstructor
public class OlaclickImporter implements CardapioImporter {

    private final HtmlFetcher fetcher;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String getNome() { return "olaclick"; }

    @Override
    public boolean suporta(URI url, String html) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
        return host.contains("olaclick");
    }

    @Override
    public ResultadoImport extrair(URI url) throws ImportException {
        String slug = extrairSlug(url);
        if (slug == null) throw new ImportException("OlaClick: não consegui detectar slug na URL.");

        // Tenta endpoints conhecidos (a API pode variar entre versões/regiões)
        String[] endpoints = {
                "https://api.olaclick.app/api/v1/companies/" + slug + "/categories",
                "https://api.olaclick.app/api/v1/companies/" + slug + "/menu",
                "https://api.olaclick.app/api/v1/companies/" + slug + "/products"
        };

        JsonNode root = null;
        String endpointOk = null;
        for (String ep : endpoints) {
            try {
                String body = fetcher.fetchJson(URI.create(ep), Map.of(
                        "Origin", "https://" + url.getHost(),
                        "Referer", url.toString()));
                JsonNode n = MAPPER.readTree(body);
                if (n != null && (n.isArray() || n.isObject())) {
                    root = n;
                    endpointOk = ep;
                    log.info("[OlaClick] api ok: {}", ep);
                    break;
                }
            } catch (Exception e) {
                log.debug("[OlaClick] {} falhou: {}", ep, e.getMessage());
            }
        }
        if (root == null) throw new ImportException("OlaClick: nenhuma API pública respondeu.");

        // Estrutura típica: { data: [ {name, products: [...]}, ... ] } ou direto array
        JsonNode lista = root.has("data") ? root.get("data") : root;
        if (!lista.isArray()) throw new ImportException("OlaClick: resposta sem lista.");

        List<CategoriaImportada> cats = new ArrayList<>();
        for (JsonNode cat : lista) {
            String nome = txt(cat, "name", "title");
            JsonNode prods = cat.path("products");
            if (!prods.isArray() || prods.size() == 0) prods = cat.path("items");
            List<ProdutoImportado> ps = new ArrayList<>();
            if (prods.isArray()) for (JsonNode p : prods) {
                ProdutoImportado pi = parse(p);
                if (pi != null) ps.add(pi);
            }
            if (!ps.isEmpty()) cats.add(CategoriaImportada.builder()
                    .nome(nome == null ? "Cardápio" : nome).produtos(ps).build());
        }
        if (cats.isEmpty()) throw new ImportException("OlaClick: API respondeu mas sem produtos. ep=" + endpointOk);

        return ResultadoImport.builder().provider(getNome()).categorias(cats).build();
    }

    private String extrairSlug(URI url) {
        String host = url.getHost();
        String path = url.getPath();
        // Caso 1: subdomain.olaclick.app → slug é o subdomain
        if (host.endsWith(".olaclick.app") && !host.startsWith("www.") && !host.startsWith("api.")) {
            return host.substring(0, host.indexOf("."));
        }
        // Caso 2: olaclick.app/SLUG[/...] → primeiro segmento do path
        if (path != null && path.length() > 1) {
            String[] parts = path.split("/");
            for (String p : parts) if (!p.isBlank()) return p;
        }
        return null;
    }

    private ProdutoImportado parse(JsonNode p) {
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

    private static String txt(JsonNode n, String... ks) {
        for (String k : ks) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static BigDecimal preco(JsonNode p) {
        for (String k : new String[]{"price", "value", "unit_price", "amount"}) {
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
        for (String k : new String[]{"image", "image_url", "photo", "picture", "thumbnail"}) {
            JsonNode v = p.get(k);
            if (v == null) continue;
            if (v.isTextual()) return v.asText();
            if (v.isObject() && v.has("url")) return v.get("url").asText();
        }
        return null;
    }
}
