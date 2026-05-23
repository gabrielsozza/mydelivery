package com.mydelivery.service.cardapio.importacao;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Fetcher HTTP único usado por todos os providers de importação. Define
 * User-Agent realista, timeout curto e segue redirects. Sem JS execution.
 *
 * Isolado num único lugar pra:
 *  - Mudar headers em UM ponto se algum site começar a bloquear.
 *  - Padronizar logs do que entra/sai.
 *  - Permitir cache futuro (V2: cache de 5min por URL).
 */
@Slf4j
@Component
public class HtmlFetcher {

    /** User-Agent atual de Chrome em macOS — passa por boa parte dos sites. */
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/126.0.0.0 Safari/537.36";

    private static final int TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(12);

    /** GET simples — devolve o Document Jsoup pronto pra select(). */
    public Document fetchHtml(URI url) throws ImportException {
        try {
            Connection conn = Jsoup.connect(url.toString())
                    .userAgent(UA)
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(false)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .header("Accept-Encoding", "gzip, deflate, br");
            Connection.Response resp = conn.execute();
            log.info("[Import] fetch {} → {} ({} bytes)",
                    url, resp.statusCode(), resp.bodyAsBytes().length);
            return resp.parse();
        } catch (Exception e) {
            log.warn("[Import] fetch falhou: {} — {}", url, e.getMessage());
            throw new ImportException("Não consegui acessar a URL: " + e.getMessage(), e);
        }
    }

    /**
     * GET pra endpoints JSON (APIs internas de provedores como OlaClick/AnotaAi).
     * Usa mesmos headers humanos pra não levantar bandeira anti-bot.
     */
    public String fetchJson(URI url, Map<String, String> headersExtras) throws ImportException {
        try {
            Connection conn = Jsoup.connect(url.toString())
                    .userAgent(UA)
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "pt-BR,pt;q=0.9");
            if (headersExtras != null) headersExtras.forEach(conn::header);
            Connection.Response resp = conn.execute();
            log.info("[Import] fetch-json {} → {} ({} bytes)",
                    url, resp.statusCode(), resp.bodyAsBytes().length);
            return resp.body();
        } catch (Exception e) {
            log.warn("[Import] fetch-json falhou: {} — {}", url, e.getMessage());
            throw new ImportException("Não consegui acessar API: " + e.getMessage(), e);
        }
    }

    /** Baixa bytes brutos — usado pelo confirm pra puxar imagem pra upload. */
    public byte[] fetchBytes(URI url) throws ImportException {
        try {
            Connection.Response resp = Jsoup.connect(url.toString())
                    .userAgent(UA)
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .maxBodySize(8 * 1024 * 1024) // 8 MB cap pra evitar abuso
                    .header("Accept", "image/*,*/*;q=0.8")
                    .execute();
            return resp.bodyAsBytes();
        } catch (Exception e) {
            throw new ImportException("Falha ao baixar bytes: " + e.getMessage(), e);
        }
    }
}
