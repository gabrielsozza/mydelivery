package com.mydelivery.service;
import com.mydelivery.model.*;
import com.mydelivery.repository.*;
import com.mydelivery.fiscal.service.NfceEmissorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class BalcaoCobrancaServiceTest {
 @Mock PedidoRepository pedidos; @Mock CaixaService caixa; @Mock NfceEmissorService fiscal;
 BalcaoCobrancaService service; Restaurante loja;
 @BeforeEach void setup(){service=new BalcaoCobrancaService(pedidos,caixa,new ObjectMapper(),fiscal);loja=new Restaurante();loja.setId(9L);TransactionSynchronizationManager.initSynchronization();}
 @AfterEach void clean(){TransactionSynchronizationManager.clearSynchronization();}
 Pedido pedido(long id,String total){Pedido p=new Pedido();p.setId(id);p.setRestaurante(loja);p.setTipo(Pedido.Tipo.BALCAO);p.setTotal(new BigDecimal(total));p.setStatus(Pedido.Status.CONFIRMADO);p.setPago(false);when(pedidos.findByIdForFiscal(id)).thenReturn(Optional.of(p));return p;}
 BalcaoCobrancaService.Parte parte(int n,String f,String v){return new BalcaoCobrancaService.Parte(n,f,new BigDecimal(v));}
 BalcaoCobrancaService.Cobranca req(List<Long> ids,BalcaoCobrancaService.Parte...partes){return new BalcaoCobrancaService.Cobranca(ids,List.of(partes));}
 @Test void divideDoisPedidosSemDuplicarValoresNemFinalizarPreparo() throws Exception {var a=pedido(1,"24.00");var b=pedido(2,"25.00");var out=service.cobrar(9L,req(List.of(1L,2L),parte(1,"PIX","24.50"),parte(2,"CARTAO_CREDITO","24.50")));assertThat(out.get("total")).isEqualTo(new BigDecimal("49.00"));assertThat(a.getPagamentosJson()).contains("24.00").doesNotContain("24.50");assertThat(b.getPagamentosJson()).contains("0.50","24.50");assertThat(a.getStatus()).isEqualTo(Pedido.Status.CONFIRMADO);assertThat(a.getPago()).isTrue();verify(caixa).registrarVendaDoPedido(9L,a);verify(caixa).registrarVendaDoPedido(9L,b);verifyNoInteractions(fiscal);TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCommit());verify(fiscal).emitirParaPedidoSeguro(1L,"sistema:cobrar-balcao",null);}
 @Test void cobraSomenteSelecionado(){var a=pedido(2,"25.00");service.cobrar(9L,req(List.of(2L),parte(1,"DINHEIRO","10"),parte(2,"PIX","15")));verify(pedidos,never()).findByIdForFiscal(1L);assertThat(a.getPagamentosJson()).contains("10.00","15.00");}
 @Test void recusaSomaIncorretaSemAlterarPedidos(){var a=pedido(1,"24.00");assertThatThrownBy(()->service.cobrar(9L,req(List.of(1L),parte(1,"PIX","23.99")))).hasMessageContaining("soma");assertThat(a.getPago()).isFalse();verify(pedidos,never()).saveAll(any());verifyNoInteractions(caixa);}
 @Test void naoCobraNovamente(){var a=pedido(1,"24.00");a.setPago(true);assertThatThrownBy(()->service.cobrar(9L,req(List.of(1L),parte(1,"PIX","24")))).hasMessageContaining("já foi pago");verifyNoInteractions(caixa);}
 @Test void isolaRestaurante(){pedido(1,"24.00");assertThatThrownBy(()->service.cobrar(10L,req(List.of(1L),parte(1,"PIX","24")))).hasMessageContaining("403");verifyNoInteractions(caixa);}
 @Test void naoAceitaMesa(){var a=pedido(1,"24.00");a.setTipo(Pedido.Tipo.MESA);assertThatThrownBy(()->service.cobrar(9L,req(List.of(1L),parte(1,"PIX","24")))).hasMessageContaining("somente pedidos de balcão");verifyNoInteractions(caixa);}
 @Test void naoAceitaCancelado(){var a=pedido(1,"24.00");a.setStatus(Pedido.Status.CANCELADO);assertThatThrownBy(()->service.cobrar(9L,req(List.of(1L),parte(1,"PIX","24")))).hasMessageContaining("cancelado");verifyNoInteractions(caixa);}
 @Test void rejeitaFormaInvalida(){pedido(1,"24");assertThatThrownBy(()->service.cobrar(9L,req(List.of(1L),parte(1,"OUTRA","24")))).hasMessageContaining("Forma de pagamento inválida");verifyNoInteractions(caixa);}
 @Test void preservaCentavosNaDivisao(){var a=pedido(1,"10.01");service.cobrar(9L,req(List.of(1L),parte(1,"PIX","5.01"),parte(2,"DINHEIRO","5.00")));assertThat(a.getPagamentosJson()).contains("5.01","5.00");}
}
