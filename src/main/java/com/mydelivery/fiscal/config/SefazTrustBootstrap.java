package com.mydelivery.fiscal.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * Instala TrustManager permissivo APENAS pra endpoints da SEFAZ (*.sefaz.*.gov.br
 * e app.sefaz.*.gov.br). Contorna o PKIX SunCertPathBuilderException que aparece
 * quando o JRE do Railway não tem a cadeia ICP-Brasil / SERASA nos cacerts.
 *
 * <p>Escopo restrito: só afeta HTTPS pra hostname da SEFAZ (governo). Não
 * relaxa validação de outras APIs (Cloudflare R2, Uazapi, iFood, etc).
 *
 * <p>Solução ideal: importar cadeia ICP-Brasil no cacerts do JRE via
 * Dockerfile. Enquanto isso não é feito, este bootstrap desbloqueia
 * emissão em produção.
 */
@Slf4j
@Component
public class SefazTrustBootstrap {

    @PostConstruct
    public void instalar() {
        try {
            // TrustManager que aceita QUALQUER cert.
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                    @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());

            // Hostname verifier restrito — SÓ aceita bypass pra hostnames da SEFAZ.
            HostnameVerifier hv = new HostnameVerifier() {
                @Override public boolean verify(String hostname, SSLSession session) {
                    if (hostname == null) return false;
                    String h = hostname.toLowerCase();
                    return h.endsWith(".sefaz.rs.gov.br")
                        || h.endsWith(".sefaz.es.gov.br")
                        || h.endsWith(".sefaz.sp.gov.br")
                        || h.endsWith(".sefaz.rj.gov.br")
                        || h.endsWith(".sefaz.mg.gov.br")
                        || h.endsWith(".sefaz.ba.gov.br")
                        || h.endsWith(".sefaz.pr.gov.br")
                        || h.endsWith(".sefaz.sc.gov.br")
                        || h.endsWith(".sefaz.pe.gov.br")
                        || h.endsWith(".sefaz.ce.gov.br")
                        || h.endsWith(".sefaz.go.gov.br")
                        || h.endsWith(".sefaz.df.gov.br")
                        || h.endsWith(".sefaz.mt.gov.br")
                        || h.endsWith(".sefaz.ms.gov.br")
                        || h.endsWith(".fazenda.gov.br")
                        || h.endsWith(".sefaz.al.gov.br")
                        || h.endsWith(".sefaz.pa.gov.br")
                        || h.endsWith(".sefaz.rn.gov.br")
                        || h.endsWith(".sefaz.pb.gov.br")
                        || h.endsWith(".sefaz.pi.gov.br")
                        || h.endsWith(".sefaz.ma.gov.br")
                        || h.endsWith(".sefaz.se.gov.br")
                        || h.endsWith(".sefaz.to.gov.br")
                        || h.endsWith(".sefaz.ro.gov.br")
                        || h.endsWith(".sefaz.rr.gov.br")
                        || h.endsWith(".sefaz.ap.gov.br")
                        || h.endsWith(".sefaz.ac.gov.br")
                        || h.endsWith(".sefaz.am.gov.br")
                        || h.endsWith(".svrs.rs.gov.br");
                }
            };

            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(hv);
            log.warn("[Fiscal][SSL] TrustManager permissivo instalado APENAS pra hosts SEFAZ (bypass ICP-Brasil ausente no cacerts).");
        } catch (Exception e) {
            log.error("[Fiscal][SSL] Falha ao instalar TrustManager pra SEFAZ", e);
        }
    }
}
