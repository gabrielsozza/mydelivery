package com.mydelivery.service.cardapio.importacao.provider;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.mydelivery.service.cardapio.importacao.HtmlFetcher;
import com.mydelivery.service.cardapio.importacao.ImportException;
import com.mydelivery.service.cardapio.importacao.dto.CategoriaImportada;
import com.mydelivery.service.cardapio.importacao.dto.ProdutoImportado;
import com.mydelivery.service.cardapio.importacao.dto.ResultadoImport;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fallback final pra páginas HTML "soltas" (sites de restaurante feitos em
 * WordPress sem schema, landing pages, etc).
 *
 * Estratégia: procura na página padrões comuns:
 *   - Elementos com classes que contêm "product", "menu-item", "dish", "prato"
 *   - Dentro, busca: título (h2/h3/h4/strong), preço (texto com R$/€/$),
 *     descrição (p), imagem (img).
 *
 * Agrupa por heading (h2/h3) anterior. Score baixo (~35) — só ganha se nenhum
 * outro provider rodou. Pode trazer ruído; o usuário edita no preview.
 */
@Slf4j
@Order(99)
@Component
@RequiredArgsConstructor
public class GenericoHtmlImporter implements CardapioImporter {

    private final HtmlFetcher fetcher;

    private static final Pattern PRECO = Pattern.compile(
            "(?:R\\$|\\$|€)\\s*(\\d{1,4}(?:[.,]\\d{2})?)|\\b(\\d{1,4}[.,]\\d{2})\\b");

    private static final String SEL_PRODUTO = String.join(",",
            ".product", ".produto", ".menu-item", ".cardapio-item",
            ".dish", ".prato", ".item-menu", ".food-item", "[data-product]",
            ".wp-block-mydelivery-item"); // padrões comuns

    @Override public String getNome() { return "html-generico"; }

    /** Suporta tudo (é o último na cadeia, só roda se nenhum outro casar). */
    @Override public boolean suporta(URI url, String html) { return html != null; }

    @Override
    public ResultadoImport extrair(URI url) throws ImportException {
        Document doc = fetcher.fetchHtml(url);

        // 1) Tentativa principal: classes conhecidas de produto
        var blocos = doc.select(SEL_PRODUTO);
        log.info("[HtmlGen] {} blocos casaram com seletores conhecidos", blocos.size());

        Map<String, List<ProdutoImportado>> porCategoria = new LinkedHashMap<>();
        for (Element bloco : blocos) {
            ProdutoImportado p = parseBloco(bloco);
            if (p == null) continue;
            String cat = headingAnterior(bloco);
            porCategoria.computeIfAbsent(cat == null ? "Cardápio" : cat, k -> new ArrayList<>()).add(p);
        }

        // 2) Tentativa secundária: se não achou nada, varre <h2>/<h3> + irmãos imediatos
        if (porCategoria.isEmpty()) {
            for (Element heading : doc.select("h2, h3")) {
                String catNome = heading.text();
                List<ProdutoImportado> ps = new ArrayList<>();
                Element next = heading.nextElementSibling();
                int hops = 0;
                while (next != null && hops < 30 && !"H2".equalsIgnoreCase(next.tagName()) && !"H3".equalsIgnoreCase(next.tagName())) {
                    ProdutoImportado p = parseBloco(next);
                    if (p != null) ps.add(p);
                    next = next.nextElementSibling();
                    hops++;
                }
                if (!ps.isEmpty()) porCategoria.put(catNome, ps);
            }
        }

        if (porCategoria.isEmpty()) throw new ImportException("Nenhum padrão de produto encontrado no HTML.");

        List<CategoriaImportada> cats = new ArrayList<>();
        porCategoria.forEach((k, v) -> cats.add(CategoriaImportada.builder().nome(k).produtos(v).build()));
        return ResultadoImport.builder().provider(getNome()).categorias(cats).build();
    }

    private ProdutoImportado parseBloco(Element bloco) {
        // Nome: prioriza heading interno; fallback: primeiro <strong>/<b>
        String nome = null;
        for (String sel : new String[]{"h1", "h2", "h3", "h4", "h5", ".title", ".name", ".product-name", "strong", "b"}) {
            Element e = bloco.selectFirst(sel);
            if (e != null && !e.text().isBlank()) { nome = e.text(); break; }
        }
        if (nome == null) return null;

        // Preço: regex no texto do bloco
        BigDecimal preco = null;
        Matcher m = PRECO.matcher(bloco.text());
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            if (raw == null) continue;
            String norm = raw.replace(".", "").replace(",", ".");
            try {
                BigDecimal b = new BigDecimal(norm);
                // Aceita 1,00 a 9999,00 — fora disso é provavelmente outra coisa (telefone, ano…)
                if (b.compareTo(BigDecimal.ONE) >= 0 && b.compareTo(new BigDecimal("9999")) <= 0) {
                    preco = b; break;
                }
            } catch (Exception ignore) {}
        }
        if (preco == null) return null;

        String desc = null;
        Element pDesc = bloco.selectFirst(".description, .desc, p");
        if (pDesc != null) {
            String t = pDesc.text();
            // Se o <p> contém o preço, ignora (não é descrição real)
            if (t != null && !t.matches(".*(?:R\\$|\\$|€)\\s*\\d.*")) desc = t;
        }

        String img = null;
        Element imgEl = bloco.selectFirst("img");
        if (imgEl != null) {
            img = imgEl.attr("src");
            if (img.isBlank()) img = imgEl.attr("data-src");
            if (img.isBlank()) img = imgEl.attr("data-lazy-src");
            if (img.startsWith("//")) img = "https:" + img;
        }

        return ProdutoImportado.builder()
                .nome(nome)
                .descricao(desc)
                .preco(preco)
                .imagemUrl(img)
                .build();
    }

    /** Walk pra trás procurando h2/h3 mais próximo — vira nome da categoria. */
    private String headingAnterior(Element bloco) {
        Element cur = bloco;
        for (int i = 0; i < 50 && cur != null; i++) {
            Element prev = cur.previousElementSibling();
            if (prev != null) {
                if ("H2".equalsIgnoreCase(prev.tagName()) || "H3".equalsIgnoreCase(prev.tagName())) {
                    return prev.text();
                }
                cur = prev;
            } else {
                cur = cur.parent();
            }
        }
        return null;
    }
}
