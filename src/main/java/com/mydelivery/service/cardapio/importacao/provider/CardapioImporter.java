package com.mydelivery.service.cardapio.importacao.provider;

import java.net.URI;

import com.mydelivery.service.cardapio.importacao.ImportException;
import com.mydelivery.service.cardapio.importacao.dto.ResultadoImport;

/**
 * Interface única implementada por cada provider de importação de cardápio.
 *
 * Adicionar suporte a novo site: criar @Component @Order(N) implementando esta
 * interface. O orquestrador descobre via Spring DI — zero mudança no resto.
 *
 * Convenção de @Order:
 *   1-10: providers específicos com API conhecida (iFood, Anota.ai, OlaClick…)
 *   50-89: padrões genéricos (JSON-LD, __NEXT_DATA__)
 *   90+: heurísticas fallback (HTML genérico)
 */
public interface CardapioImporter {

    /** Nome curto pra log/UI ("ifood", "anotaai", "olaclick", "json-ld", "html"). */
    String getNome();

    /**
     * Detection rápida — diz se este provider PODE extrair dessa URL/HTML.
     * O HTML é opcional; se o provider só decide por URL, pode passar null.
     * Deve ser cheap: sem fetch, sem parsing pesado.
     */
    boolean suporta(URI url, String htmlOpcional);

    /**
     * Faz a extração. Pode jogar ImportException sem stress — orquestrador
     * tenta o próximo provider.
     */
    ResultadoImport extrair(URI url) throws ImportException;
}
