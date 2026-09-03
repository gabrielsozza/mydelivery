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

    // ══ Relatórios pré-gerados pelo cron do fechamento mensal ═════════════

    /**
     * Grava o ZIP do fechamento mensal do CNPJ. {@code ym} no formato "yyyy-MM".
     * Retorna a URL/caminho de referência.
     */
    default String gravarRelatorio(String cnpj, String ym, byte[] zipBytes) {
        throw new UnsupportedOperationException("gravarRelatorio não implementado neste backend");
    }

    /** Lê os bytes do ZIP do fechamento (retorna null se não existe). */
    default byte[] lerRelatorio(String cnpj, String ym) { return null; }

    /** Lista os "yyyy-MM" disponíveis pra download (ordem descendente). */
    default java.util.List<String> listarRelatorios(String cnpj) { return java.util.List.of(); }
}
