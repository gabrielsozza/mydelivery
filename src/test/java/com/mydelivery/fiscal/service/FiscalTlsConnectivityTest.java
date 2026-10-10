package com.mydelivery.fiscal.service;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import javax.net.ssl.*;
import java.net.*;
class FiscalTlsConnectivityTest {
 @Test void conectaSefazComCadeiaValidadaSemDesativarTls() throws Exception {
  var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(com.mydelivery.fiscal.config.CadeiaCertificadosCA.obter());
  var ctx=SSLContext.getInstance("TLS");ctx.init(null,trust.getTrustManagers(),null);
  var conn=(HttpsURLConnection)new URI("https://nfce.svrs.rs.gov.br/ws/NfeStatusServico/NfeStatusServico4.asmx").toURL().openConnection();
  conn.setSSLSocketFactory(ctx.getSocketFactory());conn.setConnectTimeout(15000);conn.setReadTimeout(15000);conn.setRequestMethod("HEAD");
  try { assertThat(conn.getResponseCode()).isBetween(200,599); } finally {conn.disconnect();}
 }
}
