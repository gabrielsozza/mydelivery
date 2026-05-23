package com.mydelivery.service.cardapio.importacao;

/**
 * Falha controlada na importação — não é erro do servidor, é erro de fluxo
 * (URL inacessível, formato desconhecido, etc.). Caller decide se tenta o
 * próximo provider ou devolve mensagem amigável.
 */
public class ImportException extends Exception {
    public ImportException(String msg) { super(msg); }
    public ImportException(String msg, Throwable cause) { super(msg, cause); }
}
