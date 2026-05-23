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
 * Keeta — plataforma chinesa da Meituan expandindo no BR. Pouca documentação
 * pública. Este provider faz detecção por domínio + tentativa de extrair
 * JSON do HTML (geralmente window.__INIT_STATE__ ou similar).
 *
 * Se não conseguir, deixa o fallback HTML/JSON-LD tentar.
 */
@Slf4j
@Order(14)
@Component
@RequiredArgsConstructor
public class KeetaImporter implements CardapioImporter {

    private final HtmlFetcher fetcher;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String getNome() { return "keeta"; }

    @Override
    public boolean suporta(URI url, String html) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
        return host.contains("keeta.com") || host.contains("keeta.app");
    }

    @Override
    public ResultadoImport extrair(URI url) throws ImportException {
        var doc = fetcher.fetchHtml(url);
        String html = doc.outerHtml();

        for (String marker : new String[]{
                "window.__INIT_STATE__", "window.__INITIAL_STATE__",
                "window._PRELOADED_STATE_", "__NUXT__"}) {
            int idx = html.indexOf(marker);
            if (idx < 0) continue;
            int eq = html.indexOf("=", idx);
            int end = html.indexOf("</script>", eq);
            if (eq < 0 || end < 0) continue;
            String raw = html.substring(eq + 1, end).trim();
            if (raw.endsWith(";")) raw = raw.substring(0, raw.length() - 1);
            try {
                JsonNode root = MAPPER.readTree(raw);
                List<ProdutoImportado> achados = new ArrayList<>();
                walk(root, achados);
                if (!achados.isEmpty()) {
                    return ResultadoImport.builder()
                            .provider(getNome())
                            .categorias(List.of(
                                    CategoriaImportada.builder().nome("Cardápio").produtos(achados).build()))
                            .build();
                }
            } catch (Exception ignore) {}
        }
        throw new ImportException("Keeta: formato não detectado.");
    }

    private void walk(JsonNode n, List<ProdutoImportado> out) {
        if (n == null || n.isNull()) return;
        if (n.isObject()) {
            String nome = txt(n, "spuName", "name", "title");
            BigDecimal preco = preco(n);
            if (nome != null && preco != null) {
                out.add(ProdutoImportado.builder()
                        .nome(nome)
                        .descricao(txt(n, "description", "desc"))
                        .preco(preco)
                        .imagemUrl(txt(n, "picture", "image", "imageUrl"))
                        .build());
            }
            n.fields().forEachRemaining(e -> walk(e.getValue(), out));
        } else if (n.isArray()) {
            for (JsonNode c : n) walk(c, out);
        }
    }

    private static String txt(JsonNode n, String... ks) {
        for (String k : ks) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static BigDecimal preco(JsonNode p) {
        for (String k : new String[]{"price", "originPrice", "actualPrice"}) {
            JsonNode v = p.get(k);
            if (v == null) continue;
            if (v.isNumber()) {
                BigDecimal b = new BigDecimal(v.asText());
                if (b.signum() > 0 && b.scale() <= 0 && b.intValue() >= 100) {
                    return b.divide(new BigDecimal(100));
                }
                return b;
            }
        }
        return null;
    }
}
