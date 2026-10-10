package com.mydelivery.fiscal.service;
import static org.assertj.core.api.Assertions.*;
import java.security.KeyStore;
import java.math.BigDecimal;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.fincatto.documentofiscal.nfe.NFTipoEmissao;
import com.fincatto.documentofiscal.nfe.NFeConfig;
import com.fincatto.documentofiscal.nfe400.classes.nota.NFNota;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.*;
import com.fincatto.documentofiscal.nfe400.webservices.WSFacade;
class FiscalSignedQrTest {
 @Test void qrComCscZeroMantemAssinaturaXmlValida() throws Exception {
  var base=new FiscalAuditRegressionTest().req();var gw=new WmixvideoNfeGateway();
  byte[] pfx=getClass().getResourceAsStream("/fiscal/test.pfx").readAllBytes();
  NFeConfig cfg=ReflectionTestUtils.invokeMethod(gw,"novoConfig","ES",2,pfx,"senha123","000000","test-csc",NFTipoEmissao.EMISSAO_NORMAL);
  NFNota nota=ReflectionTestUtils.invokeMethod(gw,"montarNota",base,NFTipoEmissao.EMISSAO_NORMAL);
  var lote=new NFLoteEnvio();lote.setIdLote("1");lote.setVersao("4.00");lote.setIndicadorProcessamento(NFLoteIndicadorProcessamento.PROCESSAMENTO_SINCRONO);lote.setNotas(List.of(nota));
  var signed=new WSFacade(cfg).getLoteAssinado(lote);var n=signed.getNotas().get(0);
  String qr=ReflectionTestUtils.invokeMethod(gw,"montarQrCodeUrl",base,n.getInfo().getChaveAcesso());n.getInfoSuplementar().setQrCode(qr);
  assertThat(qr).contains("|2|2|0|");
  var dbf=DocumentBuilderFactory.newInstance();dbf.setNamespaceAware(true);dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
  var doc=dbf.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(signed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  var inf=(org.w3c.dom.Element)doc.getElementsByTagNameNS("http://www.portalfiscal.inf.br/nfe","infNFe").item(0);inf.setIdAttribute("Id",true);
  KeyStore ks=cfg.getCertificadoKeyStore();var cert=ks.getCertificate(ks.aliases().nextElement());
  var ctx=new DOMValidateContext(cert.getPublicKey(),doc.getElementsByTagNameNS(javax.xml.crypto.dsig.XMLSignature.XMLNS,"Signature").item(0));
  // NF-e schema uses RSA-SHA1. Permit only in this verification fixture.
  ctx.setProperty("org.jcp.xml.dsig.secureValidation",false);
  assertThat(XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx).validate(ctx)).isTrue();
  assertThat(doc.getElementsByTagNameNS("http://www.portalfiscal.inf.br/nfe","qrCode").item(0).getTextContent()).isEqualTo(qr);
 }
}
