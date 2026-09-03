package com.mydelivery.fiscal.service;

import org.springframework.stereotype.Service;

import com.mydelivery.fiscal.service.storage.XmlStorageBackend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fachada de armazenamento dos XMLs de NFC-e (5 anos por lei).
 *
 * <p>Delega ao backend ativo (injetado pelo Spring conforme
 * {@code mydelivery.fiscal.storage=local|r2}):
 * <ul>
 *   <li><b>local</b> (default) — {@code DiscoStorageBackend} — dev/teste</li>
 *   <li><b>r2</b> — {@code R2StorageBackend} — Cloudflare R2 pra produção</li>
 * </ul>
 *
 * <p>Trocar de backend sem impacto no resto do módulo — o resto do código só
 * conhece esta fachada.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NfceStorageService {

    private final XmlStorageBackend backend;

    /** Grava o XML. Retorna a URL/caminho pra referência. */
    public String gravarXml(String cnpj, String chaveAcesso, String xml) {
        return backend.gravar(cnpj, chaveAcesso, xml);
    }

    public String lerXml(String cnpj, String chaveAcesso) {
        return backend.ler(cnpj, chaveAcesso);
    }

    public String backendAtivo() { return backend.nome(); }

    // ══ Relatórios mensais pré-gerados pelo cron ═════════════════════════
    public String gravarRelatorio(String cnpj, String ym, byte[] bytes) {
        return backend.gravarRelatorio(cnpj, ym, bytes);
    }
    public byte[] lerRelatorio(String cnpj, String ym) {
        return backend.lerRelatorio(cnpj, ym);
    }
    public java.util.List<String> listarRelatorios(String cnpj) {
        return backend.listarRelatorios(cnpj);
    }
}
