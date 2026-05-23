package com.mydelivery.service.cardapio.importacao;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.springframework.stereotype.Service;

import com.mydelivery.service.cardapio.importacao.dto.ResultadoImport;
import com.mydelivery.service.cardapio.importacao.provider.CardapioImporter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orquestrador da importação. Recebe URL, escolhe o primeiro provider que
 * suporta, extrai, normaliza e devolve. Se o provider falhar, tenta o próximo.
 *
 * Não persiste nada — preview é stateless. A persistência fica no confirm
 * separado (CardapioImportConfirmService).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardapioImportService {

    /** Spring injeta TODOS os @Components implementando CardapioImporter, ordenados por @Order. */
    private final List<CardapioImporter> providers;
    private final HtmlFetcher htmlFetcher;
    private final ImportNormalizer normalizer;

    public ResultadoImport importarPreview(String urlBruta) throws ImportException {
        URI url = validarUrl(urlBruta);

        // Fetch apenas UMA vez. Cada provider que precisar de HTML pode usar o doc carregado.
        // Providers que chamam API própria fazem fetch isolado dentro do extrair().
        String htmlBaixado;
        try {
            htmlBaixado = htmlFetcher.fetchHtml(url).outerHtml();
        } catch (ImportException e) {
            // Site retornou 4xx/5xx/timeout — sem html, providers HTML-based vão ignorar,
            // mas providers de API direta (iFood, OlaClick) ainda podem funcionar.
            log.info("[Import] fetch inicial falhou — tentando providers de API. {}", e.getMessage());
            htmlBaixado = null;
        }

        for (CardapioImporter prov : providers) {
            if (!prov.suporta(url, htmlBaixado)) continue;
            log.info("[Import] tentando provider {} pra {}", prov.getNome(), url);
            try {
                ResultadoImport r = prov.extrair(url);
                if (r == null) {
                    log.info("[Import] provider {} devolveu null — pulando", prov.getNome());
                    continue;
                }
                r.setUrlOrigem(url.toString());
                ResultadoImport norm = normalizer.normalizar(r);
                if (norm.getTotalProdutos() == 0) {
                    log.info("[Import] provider {} não achou produtos após normalizar — pulando",
                            prov.getNome());
                    continue;
                }
                log.info("[Import] provider {} extraiu {} produto(s), score={}",
                        prov.getNome(), norm.getTotalProdutos(), norm.getScore());
                return norm;
            } catch (Exception e) {
                log.info("[Import] provider {} falhou: {} — tentando próximo",
                        prov.getNome(), e.getMessage());
            }
        }

        throw new ImportException(
                "Não consegui importar esse cardápio automaticamente. "
              + "A página pode estar protegida ou ter formato incompatível. "
              + "Você pode cadastrar os produtos manualmente.");
    }

    private URI validarUrl(String urlBruta) throws ImportException {
        if (urlBruta == null || urlBruta.isBlank()) {
            throw new ImportException("URL obrigatória.");
        }
        String u = urlBruta.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        try {
            URI uri = new URI(u);
            if (uri.getHost() == null) throw new ImportException("URL inválida.");
            return uri;
        } catch (URISyntaxException e) {
            throw new ImportException("URL inválida: " + e.getMessage());
        }
    }
}
