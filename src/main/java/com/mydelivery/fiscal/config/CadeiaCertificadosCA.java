package com.mydelivery.fiscal.config;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Carrega uma cadeia de certificados raiz confiáveis pra usar como truststore
 * na lib fincatto (retornada em {@code NFeConfig.getCadeiaCertificadosKeyStore()}).
 *
 * <p>Fonte: bundle Mozilla via curl.se/ca (cacert.pem) — contém ICP-Brasil e
 * todas as CAs raiz públicas. Baixado no boot e cacheado em memória.
 * Se o download falha (rede/proxy), cai em {@code null} → lib usa cacerts
 * padrão do JRE (que no Railway não tem ICP-Brasil = PKIX error).
 */
@Slf4j
public final class CadeiaCertificadosCA {

    private static final String CA_BUNDLE_URL = "https://curl.se/ca/cacert.pem";
    private static volatile KeyStore CACHE;
    private static volatile boolean CARREGADO = false;

    private CadeiaCertificadosCA() {}

    /** Devolve o KeyStore com CAs confiáveis. Lazy-load 1x no primeiro uso. */
    public static synchronized KeyStore obter() {
        if (CARREGADO) return CACHE;
        CARREGADO = true;
        try {
            byte[] pem = new URL(CA_BUNDLE_URL).openStream().readAllBytes();
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            @SuppressWarnings("unchecked")
            Collection<? extends X509Certificate> certs =
                    (Collection<? extends X509Certificate>) cf.generateCertificates(new ByteArrayInputStream(pem));
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            List<String> aliases = new ArrayList<>();
            for (X509Certificate c : certs) {
                String alias = "ca-" + i++;
                ks.setCertificateEntry(alias, c);
                aliases.add(alias);
            }
            log.info("[Fiscal][CA] Bundle CAs carregado — {} certs (Mozilla + ICP-Brasil).", certs.size());
            CACHE = ks;
            return ks;
        } catch (Exception e) {
            log.error("[Fiscal][CA] Falha ao baixar bundle CA — SEFAZ pode dar PKIX. Motivo: {}", e.toString());
            CACHE = null;
            return null;
        }
    }
}
