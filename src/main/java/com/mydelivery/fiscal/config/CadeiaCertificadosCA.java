package com.mydelivery.fiscal.config;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;

/**
 * Monta um KeyStore com CAs raiz confiáveis pra usar como truststore da
 * lib fincatto (retornado em {@code NFeConfig.getCadeiaCertificadosKeyStore()}).
 *
 * <p>Estratégia em camadas:
 * <ol>
 *   <li>Copia todos os certs do cacerts padrão do JRE.</li>
 *   <li>Adiciona bundle Mozilla via curl.se/ca (tem ICP-Brasil desde 2022).</li>
 *   <li>Adiciona bundle Mozilla via curl.se/ca (backup).</li>
 * </ol>
 * Se todos falharem, devolve o cacerts do JRE puro.
 */
@Slf4j
public final class CadeiaCertificadosCA {

    private static final String[] URLS_BUNDLE = {
        "https://curl.se/ca/cacert.pem",
        "https://raw.githubusercontent.com/mozilla/gecko-dev/master/security/nss/lib/ckfw/builtins/certdata.txt",
    };

    private static volatile KeyStore CACHE;
    private static volatile boolean CARREGADO = false;

    private CadeiaCertificadosCA() {}

    /** Devolve o KeyStore com CAs confiáveis. Lazy-load 1x no primeiro uso. */
    public static synchronized KeyStore obter() {
        if (CARREGADO) return CACHE;
        CARREGADO = true;
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);

            // 1) Copia cacerts default do JRE.
            int jreCerts = adicionarCacertsDoJre(ks);
            log.info("[Fiscal][CA] cacerts JRE carregado: {} certs", jreCerts);

            // 2) Baixa e adiciona bundle Mozilla (contém ICP-Brasil).
            int extras = 0;
            for (String url : URLS_BUNDLE) {
                int add = tentarBaixarECarregar(ks, url);
                if (add > 0) { extras += add; break; }
            }
            log.info("[Fiscal][CA] Total truststore: {} certs (JRE {} + extras {}).",
                    jreCerts + extras, jreCerts, extras);
            CACHE = ks;
            return ks;
        } catch (Exception e) {
            log.error("[Fiscal][CA] Falha total ao montar CA — SEFAZ vai dar PKIX. Motivo: {}", e.toString());
            CACHE = null;
            return null;
        }
    }

    private static int adicionarCacertsDoJre(KeyStore destino) {
        String[] paths = {
            System.getProperty("javax.net.ssl.trustStore"),
            System.getProperty("java.home") + "/lib/security/cacerts",
            "/etc/ssl/certs/java/cacerts",
        };
        for (String path : paths) {
            if (path == null) continue;
            File f = new File(path);
            if (!f.exists()) continue;
            try (FileInputStream fis = new FileInputStream(f)) {
                KeyStore jre = KeyStore.getInstance(KeyStore.getDefaultType());
                String pass = System.getProperty("javax.net.ssl.trustStorePassword", "changeit");
                jre.load(fis, pass.toCharArray());
                int n = 0;
                var aliases = jre.aliases();
                while (aliases.hasMoreElements()) {
                    String a = aliases.nextElement();
                    if (jre.isCertificateEntry(a)) {
                        try {
                            destino.setCertificateEntry("jre-" + a, jre.getCertificate(a));
                            n++;
                        } catch (Exception ignore) {}
                    }
                }
                return n;
            } catch (Exception e) {
                log.debug("[Fiscal][CA] cacerts em {} falhou: {}", path, e.toString());
            }
        }
        return 0;
    }

    private static int tentarBaixarECarregar(KeyStore destino, String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            byte[] pem = conn.getInputStream().readAllBytes();
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            @SuppressWarnings("unchecked")
            Collection<? extends X509Certificate> certs =
                    (Collection<? extends X509Certificate>) cf.generateCertificates(new ByteArrayInputStream(pem));
            int i = 0;
            for (X509Certificate c : certs) {
                try {
                    destino.setCertificateEntry("bundle-" + System.nanoTime() + "-" + (i++), c);
                } catch (Exception ignore) {}
            }
            log.info("[Fiscal][CA] Bundle {} → {} certs adicionados.", url, i);
            return i;
        } catch (Exception e) {
            log.warn("[Fiscal][CA] Falha baixar {}: {}", url, e.toString());
            return 0;
        }
    }
}
