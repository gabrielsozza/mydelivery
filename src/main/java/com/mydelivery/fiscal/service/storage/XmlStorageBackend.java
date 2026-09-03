package com.mydelivery.fiscal.service.storage;

/**
 * Abstração de storage pra os XMLs de NFC-e (5 anos por lei).
 *
 * <p>Duas impls: {@code DiscoStorageBackend} (dev/local) e
 * {@code R2StorageBackend} (Cloudflare R2 em produção). Ativação por
 * property {@code mydelivery.fiscal.storage=local|r2}.
 */
public interface XmlStorageBackend {

    /** Grava o XML. Retorna a URL/caminho pra referência (fica em {@code NotaFiscalEmitida.xmlUrl}). */
    String gravar(String cnpj, String chaveAcesso, String xml);

    /** Lê o XML pelo par (cnpj, chave). Retorna null se não encontrar. */
    String ler(String cnpj, String chaveAcesso);

    /** Rótulo do backend pra log/debug. */
    String nome();
}
