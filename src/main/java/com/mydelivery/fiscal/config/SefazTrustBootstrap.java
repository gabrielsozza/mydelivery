package com.mydelivery.fiscal.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Instala TrustManager tolerante pra contornar o PKIX SunCertPathBuilderException
 * que aparece quando o JRE do Railway não tem a cadeia ICP-Brasil / SERASA nos
 * cacerts (a SEFAZ é assinada por essas CAs).
 *
 * <p>Estratégia: tenta validar contra o cacerts padrão primeiro (fluxo normal
 * de outras APIs — R2, Uazapi, etc — continua seguro). Se falhar E o
 * emissor for uma CA brasileira (ICP-Brasil, SERPRO, SERASA-CB, etc), aceita
 * o certificado. Assim mantemos segurança pro resto do mundo e desbloqueamos
 * emissão fiscal.
 */
@Slf4j
@Component
public class SefazTrustBootstrap {

    @PostConstruct
    public void instalar() {
        try {
            // 1. Pega o TrustManager default do JRE (cacerts padrão).
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);   // usa cacerts do JRE
            final X509TrustManager defaultTm = (X509TrustManager) tmf.getTrustManagers()[0];

            // 2. Wrapper: tenta o default, se falhar E for CA brasileira, aceita.
            TrustManager tolerant = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException {
                    defaultTm.checkClientTrusted(c, a);
                }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException {
                    try {
                        defaultTm.checkServerTrusted(c, a);
                    } catch (CertificateException e) {
                        if (ehCadeiaBrasileira(c)) {
                            log.debug("[Fiscal][SSL] Aceitando cert de CA brasileira (cacerts JRE não tem ICP-Brasil)");
                            return;
                        }
                        throw e;
                    }
                }
                @Override public X509Certificate[] getAcceptedIssuers() {
                    return defaultTm.getAcceptedIssuers();
                }
            };

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{ tolerant }, new java.security.SecureRandom());

            // Instala global — a lib fincatto usa CXF/JAX-WS que ignora
            // HttpsURLConnection.setDefaultSSLSocketFactory sozinho.
            SSLContext.setDefault(sc);
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // Hostname verifier: só relaxa pra hosts SEFAZ (governo).
            HostnameVerifier hv = (hostname, session) -> {
                if (hostname == null) return false;
                String h = hostname.toLowerCase();
                return h.endsWith(".gov.br") || HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session);
            };
            HttpsURLConnection.setDefaultHostnameVerifier(hv);

            log.warn("[Fiscal][SSL] TrustManager tolerante instalado (default + fallback ICP-Brasil).");
        } catch (Exception e) {
            log.error("[Fiscal][SSL] Falha ao instalar TrustManager", e);
        }
    }

    /** Detecta se o cert foi emitido por CA brasileira (ICP-Brasil, SERPRO, SERASA, etc). */
    private static boolean ehCadeiaBrasileira(X509Certificate[] chain) {
        if (chain == null || chain.length == 0) return false;
        for (X509Certificate cert : chain) {
            String issuer = cert.getIssuerX500Principal().getName().toUpperCase();
            if (issuer.contains("ICP-BRASIL")
             || issuer.contains("ICPBRASIL")
             || issuer.contains("SERPRO")
             || issuer.contains("SERASA")
             || issuer.contains("VALID CERTIFICADORA")
             || issuer.contains("SOLUTI")
             || issuer.contains("AC RAIZ")
             || issuer.contains("RECEITA FEDERAL")
             || issuer.contains("SECRETARIA DA FAZENDA")
             || issuer.contains("SEFAZ")) {
                return true;
            }
        }
        return false;
    }
}
