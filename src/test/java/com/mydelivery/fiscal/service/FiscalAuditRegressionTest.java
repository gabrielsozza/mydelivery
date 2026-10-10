package com.mydelivery.fiscal.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.fincatto.documentofiscal.nfe.NFTipoEmissao;
import com.fincatto.documentofiscal.nfe400.classes.evento.*;
import com.fincatto.documentofiscal.nfe400.classes.lote.envio.*;
import com.fincatto.documentofiscal.nfe400.classes.nota.*;
import com.fincatto.documentofiscal.nfe400.classes.NFProtocoloInfo;
import com.mydelivery.fiscal.service.NfeGateway.*;
import com.mydelivery.fiscal.model.NotaFiscalEmitida;
import com.mydelivery.fiscal.repository.NotaFiscalEmitidaRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.Restaurante;
class FiscalAuditRegressionTest {
  RequisicaoEmissao req() {
    var emit=new Emitente("12345678000199","LOJA TESTE LTDA","Teste","ISENTO","ES","3205309","Vitoria","Rua Teste","10","Centro","29010000",1);
    var itens=List.of(new ItemNota(1,"1","Acai","21069090","5102",null,"102",0,"UN",BigDecimal.ONE,new BigDecimal("25.90"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));
    return new RequisicaoEmissao("ES",2,1,1L,LocalDateTime.now(),emit,null,itens,new BigDecimal("26.90"),List.of(new Pagamento("01",new BigDecimal("26.90"))),"000000","test-csc",new byte[]{1},"senha",new BigDecimal("2.00"),new BigDecimal("3.00"));
  }
  @Test void notaIncluiFreteDescontoETotalPago() {
    NFNota nota=ReflectionTestUtils.invokeMethod(new WmixvideoNfeGateway(),"montarNota",req(),NFTipoEmissao.EMISSAO_NORMAL);
    String xml=nota.toString();
    assertThat(xml).contains("<vProd>25.90</vProd>","<vDesc>2.00</vDesc>","<vFrete>3.00</vFrete>","<vNF>26.90</vNF>","<vPag>26.90</vPag>");
  }
  @Test void xmlProcessadoContemNotaEProtocoloCorrespondente() {
    NFNota nota=ReflectionTestUtils.invokeMethod(new WmixvideoNfeGateway(),"montarNota",req(),NFTipoEmissao.EMISSAO_NORMAL);
    var lote=new NFLoteEnvio();lote.setNotas(List.of(nota));
    var info=new NFProtocoloInfo(); info.setChave(nota.getInfo().getChaveAcesso()); info.setAmbiente(com.fincatto.documentofiscal.DFAmbiente.HOMOLOGACAO);info.setVersaoAplicacao("TEST");info.setDataRecebimento("2026-09-22T12:00:00-03:00");info.setValidador("dGVzdA==");info.setMotivo("Autorizado o uso da NF-e");info.setStatus("100"); info.setNumeroProtocolo("132260000000001");
    var retorno=new NFLoteEnvioRetorno();retorno.setProtocoloInfo(info);
    var dados=new NFLoteEnvioRetornoDados(retorno,lote);
    assertThat(WmixvideoNfeGateway.extrairXmlProc(dados)).contains("<nfeProc", "<NFe", "<protNFe", "132260000000001");
    info.setChave("1".repeat(44));assertThatThrownBy(()->WmixvideoNfeGateway.extrairXmlProc(dados)).isInstanceOf(IllegalStateException.class);
  }
  @Test void loteProcessadoNaoSignificaCancelamento() {
    var ret=mock(NFEnviaEventoRetorno.class,RETURNS_DEEP_STUBS);when(ret.getCodigoStatusReposta()).thenReturn(128);when(ret.getEventoRetorno()).thenReturn(List.of());
    assertThat(WmixvideoNfeGateway.interpretarCancelamento(ret,"1".repeat(44)).aprovado()).isFalse();
  }
  @Test void cancelamentoExigeEventoChaveEProtocolo() {
    var ret=mock(NFEnviaEventoRetorno.class);var evento=mock(NFEventoRetorno.class,RETURNS_DEEP_STUBS);var info=evento.getInfoEventoRetorno();
    when(ret.getEventoRetorno()).thenReturn(List.of(evento));when(info.getChave()).thenReturn("1".repeat(44));when(info.getCodigoStatus()).thenReturn(135);when(info.getNumeroProtocolo()).thenReturn("132260000000002");
    assertThat(WmixvideoNfeGateway.interpretarCancelamento(ret,"1".repeat(44)).aprovado()).isTrue();
    when(info.getCodigoStatus()).thenReturn(155);assertThat(WmixvideoNfeGateway.interpretarCancelamento(ret,"1".repeat(44)).aprovado()).isTrue();
    when(info.getCodigoStatus()).thenReturn(573);assertThat(WmixvideoNfeGateway.interpretarCancelamento(ret,"1".repeat(44)).aprovado()).isFalse();
    when(info.getCodigoStatus()).thenReturn(135);when(info.getNumeroProtocolo()).thenReturn(null);assertThat(WmixvideoNfeGateway.interpretarCancelamento(ret,"1".repeat(44)).aprovado()).isFalse();
    assertThat(WmixvideoNfeGateway.interpretarCancelamento(ret,"2".repeat(44)).aprovado()).isFalse();
  }
  @Test void autorizadaNaoRepeteEmissaoETentativaIncertaNaoTrocaNumero() {
    var repo=mock(PedidoRepository.class);var notas=mock(NotaFiscalEmitidaRepository.class);var gateway=mock(NfeGateway.class);
    var service=new NfceEmissorService(null,null,null,notas,null,null,repo,null,null,null,null,gateway);
    var r=new Restaurante();r.setId(9L);var p=new Pedido();p.setRestaurante(r);when(repo.findByIdForFiscal(7L)).thenReturn(Optional.of(p));
    var nota=NotaFiscalEmitida.builder().status(NotaFiscalEmitida.Status.AUTORIZADA).build();when(notas.findByPedidoIdForUpdate(7L)).thenReturn(List.of(nota));
    assertThat(service.emitirParaPedido(7L,"test",null)).isSameAs(nota);verifyNoInteractions(gateway);
    nota.setStatus(NotaFiscalEmitida.Status.ENVIANDO);assertThatThrownBy(()->service.emitirParaPedido(7L,"test",null)).isInstanceOf(IllegalStateException.class);verifyNoInteractions(gateway);
  }
  @Test void bootstrapPreservaValidacaoTlsGlobal() {
    var factory=javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory();var verifier=javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier();
    new com.mydelivery.fiscal.config.SefazTrustBootstrap().instalar();
    assertThat(javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()).isSameAs(factory);assertThat(javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()).isSameAs(verifier);
  }
}
