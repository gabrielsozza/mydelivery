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
 * 99Food (food.99app.com / 99food.com.br). Plataforma nova da 99/DiDi —
 * frontend React, dados via API REST. Endpoints exatos podem variar; este
 * provider tenta variações conhecidas, e cai pro NextData/HTML fallback se
 * nenhuma der.
 */
@Slf4j
@Order(13)
@Component
@RequiredArgsConstructor
public class NoveFoodImporter implements CardapioImporter {

    private final HtmlFetcher fetcher;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String getNome() { return "99food"; }

    @Override
    public boolean suporta(URI url, String html) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
        return host.contains("99food") || host.contains("food.99app") || host.contains("99app.com");
    }

    @Override
    public ResultadoImport extrair(URI url) throws ImportException {
        // Estratégia: tenta achar o ID do merchant na URL e bater na API.
        // Como 99Food ainda não tem API pública estável documentada, primeiro
        // tentamos o HTML pra ver se tem JSON embutido.
        var doc = fetcher.fetchHtml(url);

        // Procura JSONs grandes no HTML — 99Food pode embutir initial state em
        // __INITIAL_STATE__ ou __PRELOADED_STATE__
        String html = doc.outerHtml();
        for (String marker : new String[]{"__INITIAL_STATE__", "__PRELOADED_STATE__", "window.__DATA__"}) {
            int idx = html.indexOf(marker);
            if (idx < 0) continue;
            int eq = html.indexOf("=", idx);
            int end = html.indexOf("</script>", eq);
            if (eq < 0 || end < 0) continue;
            String jsonRaw = html.substring(eq + 1, end).trim();
            if (jsonRaw.endsWith(";")) jsonRaw = jsonRaw.substring(0, jsonRaw.length() - 1);
            try {
                JsonNode node = MAPPER.readTree(jsonRaw);
                ResultadoImport r = walkProcurandoMenu(node);
                if (r != null && r.getTotalProdutos() > 0) return r;
            } catch (Exception ignore) { /* tenta próximo */ }
        }

        // Tenta endpoint REST guessado
        String merchantId = ultimoSegmentoNumerico(url);
        if (merchantId != null) {
            try {
                String body = fetcher.fetchJson(
                        URI.create("https://food.99app.com/api/restaurants/" + merchantId + "/menu"),
                        Map.of("Referer", url.toString()));
                JsonNode root = MAPPER.readTree(body);
                ResultadoImport r = walkProcurandoMenu(root);
                if (r != null && r.getTotalProdutos() > 0) return r;
            } catch (Exception e) {
                log.debug("[99Food] api guess falhou: {}", e.getMessage());
            }
        }

        throw new ImportException("99Food: formato não detectado. Fallback genérico tentará a seguir.");
    }

    /** Walk simples buscando array que pareça produtos. Reusa lógica do NextDataImporter. */
    private ResultadoImport walkProcurandoMenu(JsonNode root) {
        List<ProdutoImportado> achados = new ArrayList<>();
        walk(root, achados);
        if (achados.isEmpty()) return null;
        CategoriaImportada cat = CategoriaImportada.builder().nome("Cardápio").produtos(achados).build();
        return ResultadoImport.builder().provider(getNome())
                .categorias(List.of(cat)).build();
    }

    private void walk(JsonNode n, List<ProdutoImportado> out) {
        if (n == null || n.isNull()) return;
        if (n.isObject()) {
            String nome = txt(n, "name", "title", "productName");
            BigDecimal preco = preco(n);
            if (nome != null && preco != null) {
                out.add(ProdutoImportado.builder()
                        .nome(nome)
                        .descricao(txt(n, "description", "desc"))
                        .preco(preco)
                        .imagemUrl(txt(n, "image", "imageUrl", "picUrl"))
                        .build());
            }
            n.fields().forEachRemaining(e -> walk(e.getValue(), out));
        } else if (n.isArray()) {
            for (JsonNode c : n) walk(c, out);
        }
    }

    private String ultimoSegmentoNumerico(URI url) {
        if (url.getPath() == null) return null;
        String[] segs = url.getPath().split("/");
        for (int i = segs.length - 1; i >= 0; i--) {
            if (segs[i].matches("\\d+")) return segs[i];
        }
        return null;
    }

    private static String txt(JsonNode n, String... ks) {
        for (String k : ks) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static BigDecimal preco(JsonNode p) {
        for (String k : new String[]{"price", "originPrice", "minPrice", "unitPrice"}) {
            JsonNode v = p.get(k);
            if (v == null) continue;
            if (v.isNumber()) {
                BigDecimal b = new BigDecimal(v.asText());
                // 99Food costuma armazenar em centavos
                if (b.signum() > 0 && b.scale() <= 0 && b.intValue() >= 100) {
                    return b.divide(new BigDecimal(100));
                }
                return b;
            }
        }
        return null;
    }
}
