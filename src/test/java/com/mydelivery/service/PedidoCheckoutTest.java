package com.mydelivery.service;

import com.mydelivery.dto.pedido.NovoPedidoRequest;
import com.mydelivery.model.*;
import com.mydelivery.repository.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class PedidoCheckoutTest {
    @Mock PagamentoService pagamentoService;
    @Mock PedidoRepository pedidos;
    @Mock RestauranteRepository restaurantes;
    @Mock ProdutoRepository produtos;
    @Mock ClienteRepository clientes;
    @Mock HorarioLojaService horarios;
    @Mock ComplementoGrupoRepository complementos;
    @InjectMocks PedidoService service;

    @ParameterizedTest
    @CsvSource({"pix,entrega,PIX,NA_ENTREGA", "credito,entrega,CARTAO_CREDITO,NA_ENTREGA",
        "debito,entrega,CARTAO_DEBITO,NA_ENTREGA", "dinheiro,entrega,DINHEIRO,NA_ENTREGA",
        "pix,online,PIX,ONLINE", "credito,online,CARTAO_CREDITO,ONLINE"})
    void enderecoLongoClienteRecorrenteEPagamentoCorreto(String metodo,String modo,Pedido.FormaPagamento forma,Pedido.ModoPagamento tipo) {
        var r=new Restaurante();r.setId(58L);r.setSlug("loja-teste");r.setAberto(true);r.setModoTaxa("BAIRRO");
        r.setBairrosAtendidos(List.of(new BairroEntrega("Parque das Orquídeas",new BigDecimal("5.00"))));
        when(restaurantes.findBySlug("loja-teste")).thenReturn(Optional.of(r));
        when(horarios.calcular(r)).thenReturn(new HorarioLojaService.EstadoHorario());
        var cliente=new Cliente();cliente.setId(1L);cliente.setRestaurante(r);cliente.setTotalPedidos(3);
        when(clientes.findFirstByTelefoneAndRestauranteIdOrderByIdAsc("11999999999",58L)).thenReturn(Optional.of(cliente));
        var produto=new Produto();produto.setId(2L);produto.setNome("Açaí teste");produto.setPreco(new BigDecimal("20.00"));
        when(produtos.findById(2L)).thenReturn(Optional.of(produto));
        when(pedidos.saveAndFlush(any())).thenAnswer(i->{Pedido p=i.getArgument(0);p.setId(99L);return p;});
        var req=new NovoPedidoRequest();req.setSlug("loja-teste");req.setModo("delivery");req.setPagamento(metodo);req.setModoPagamento(modo);
        var c=new NovoPedidoRequest.ClienteDto();c.setNome("Teste");c.setTelefone("(11) 99999-9999");req.setCliente(c);
        String numero="123 bloco A casa dos fundos";
        req.setEndereco(Map.of("rua","Rua Teste","numero",numero,"bairro","parque das orquideas","referencia","x".repeat(200),"complemento","Bloco dos fundos"));
        var item=new NovoPedidoRequest.ItemDto();item.setProdutoId(2L);item.setQty(2);req.setItens(List.of(item));
        var resposta=service.criarPedido(req);
        assertThat(resposta.getId()).isEqualTo(99L);
        assertThat(resposta.getFormaPagamento()).isEqualTo(forma);
        assertThat(resposta.getModoPagamento()).isEqualTo(tipo);
        assertThat(resposta.getTotal()).isEqualByComparingTo("45.00");
        assertThat(resposta.getEnderecoEntrega()).contains(numero).hasSizeGreaterThan(255);
        assertThat(cliente.getEnderecoNumero()).isEqualTo(numero);
        assertThat(cliente.getTotalPedidos()).isEqualTo(4);
        verify(pedidos,times(1)).saveAndFlush(any());
        verify(clientes,never()).findByTelefoneAndRestauranteId(anyString(),anyLong());
    }

    @ParameterizedTest
    @CsvSource({"pix,15.00", "dinheiro,15.00", "debito,17.00", "credito,17.00"})
    void cobraPrecoConfiguradoParaFormaDePagamento(String metodo, String base) {
        var r=new Restaurante();r.setId(58L);r.setSlug("loja-teste");r.setAberto(true);
        r.setSomenteRetiradaAtivo(true);
        when(restaurantes.findBySlug("loja-teste")).thenReturn(Optional.of(r));
        when(horarios.calcular(r)).thenReturn(new HorarioLojaService.EstadoHorario());
        var c=new Cliente();c.setId(1L);c.setRestaurante(r);c.setTotalPedidos(0);
        when(clientes.findFirstByTelefoneAndRestauranteIdOrderByIdAsc("11999999999",58L)).thenReturn(Optional.of(c));
        var p=new Produto();p.setId(2L);p.setNome("Produto");p.setPreco(new BigDecimal("15.00"));
        p.setPrecoPix(new BigDecimal("15.00"));p.setPrecoDinheiro(new BigDecimal("15.00"));
        p.setPrecoDebito(new BigDecimal("17.00"));p.setPrecoCredito(new BigDecimal("17.00"));
        when(produtos.findById(2L)).thenReturn(Optional.of(p));
        when(pedidos.saveAndFlush(any())).thenAnswer(i->{Pedido pedido=i.getArgument(0);pedido.setId(99L);return pedido;});
        var req=new NovoPedidoRequest();req.setSlug("loja-teste");req.setModo("retirada");req.setPagamento(metodo);req.setModoPagamento("entrega");
        var cliente=new NovoPedidoRequest.ClienteDto();cliente.setNome("Teste");cliente.setTelefone("11999999999");req.setCliente(cliente);
        var item=new NovoPedidoRequest.ItemDto();item.setProdutoId(2L);item.setQty(2);
        item.setPreco(new BigDecimal(base));item.setPrecoBasePagamento(new BigDecimal(base));req.setItens(List.of(item));
        assertThat(service.criarPedido(req).getTotal()).isEqualByComparingTo(new BigDecimal(base).multiply(BigDecimal.valueOf(2)));
    }

    @Test
    void salvaMeioAMeioPeloMaiorSaborIncluindoBebidaEEntrega() {
        var r=new Restaurante();r.setId(63L);r.setSlug("casole-pizzaria");r.setAberto(true);r.setModoTaxa("BAIRRO");
        r.setBairrosAtendidos(List.of(new BairroEntrega("Centro",new BigDecimal("7.00"))));
        when(restaurantes.findBySlug(r.getSlug())).thenReturn(Optional.of(r));
        when(horarios.calcular(r)).thenReturn(new HorarioLojaService.EstadoHorario());
        var c=new Cliente();c.setId(1L);c.setRestaurante(r);c.setTotalPedidos(0);
        when(clientes.findFirstByTelefoneAndRestauranteIdOrderByIdAsc("11999999999",63L)).thenReturn(Optional.of(c));
        var p=PrecoComplementosMaiorTest.pizza();p.setId(2225L);p.setNome("Pizza Família");
        when(produtos.findById(2225L)).thenReturn(Optional.of(p));
        when(complementos.findByProdutoIdOrderByIdAsc(2225L)).thenReturn(PrecoComplementosMaiorTest.grupos());
        when(pedidos.saveAndFlush(any())).thenAnswer(i->{Pedido pedido=i.getArgument(0);pedido.setId(99L);return pedido;});
        var req=new NovoPedidoRequest();req.setSlug(r.getSlug());req.setModo("delivery");req.setPagamento("debito");req.setModoPagamento("entrega");
        var cliente=new NovoPedidoRequest.ClienteDto();cliente.setNome("Teste");cliente.setTelefone("11999999999");req.setCliente(cliente);
        req.setEndereco(Map.of("rua","Rua Teste","numero","1","bairro","Centro"));
        var item=PrecoComplementosMaiorTest.pedido();item.setProdutoId(2225L);item.setQty(1);req.setItens(List.of(item));
        var resposta=service.criarPedido(req);
        assertThat(resposta.getSubtotal()).isEqualByComparingTo("51.80");
        assertThat(resposta.getTotal()).isEqualByComparingTo("58.80");
        assertThat(resposta.getItens().getFirst().getPrecoUnitario()).isEqualByComparingTo("51.80");
    }

    @Test
    void cervejaDePrecoVariavelNaoSomaPrecoReferencialAoSabor() {
        var r=new Restaurante();r.setId(63L);r.setSlug("talentos-pizzas");r.setAberto(true);
        when(restaurantes.findBySlug(r.getSlug())).thenReturn(Optional.of(r));
        when(horarios.calcular(r)).thenReturn(new HorarioLojaService.EstadoHorario());
        var c=new Cliente();c.setId(1L);c.setRestaurante(r);c.setTotalPedidos(0);
        when(clientes.findFirstByTelefoneAndRestauranteIdOrderByIdAsc("11999999999",63L)).thenReturn(Optional.of(c));
        var p=new Produto();p.setId(2363L);p.setNome("Cervejas");p.setPreco(new BigDecimal("8.00"));p.setPrecoVitrine(true);
        when(produtos.findById(2363L)).thenReturn(Optional.of(p));
        when(complementos.findByProdutoIdOrderByIdAsc(2363L)).thenReturn(List.of(
                PrecoComplementosMaiorTest.grupo(1913,ComplementoGrupo.ModoPreco.SOMA,1,5,
                        PrecoComplementosMaiorTest.item(63626,"Skol 330 ml","8.00"))));
        when(pedidos.saveAndFlush(any())).thenAnswer(i->{Pedido pedido=i.getArgument(0);pedido.setId(99L);return pedido;});
        var req=new NovoPedidoRequest();req.setSlug(r.getSlug());req.setModo("retirada");req.setPagamento("pix");req.setModoPagamento("entrega");
        var cliente=new NovoPedidoRequest.ClienteDto();cliente.setNome("Teste");cliente.setTelefone("11999999999");req.setCliente(cliente);
        var item=new NovoPedidoRequest.ItemDto();item.setProdutoId(2363L);item.setQty(1);
        item.setPreco(new BigDecimal("16.00"));item.setComplementos(List.of(PrecoComplementosMaiorTest.escolha(1913,63626,1)));
        req.setItens(List.of(item));
        var resposta=service.criarPedido(req);
        assertThat(resposta.getSubtotal()).isEqualByComparingTo("8.00");
        assertThat(resposta.getItens().getFirst().getPrecoUnitario()).isEqualByComparingTo("8.00");
    }

    @Test
    void recusaDeliveryQuandoSomenteRetiradaEstaAtiva() {
        var r = new Restaurante();
        r.setSlug("loja-teste");
        r.setAberto(true);
        r.setSomenteRetiradaAtivo(true);
        when(restaurantes.findBySlug("loja-teste")).thenReturn(Optional.of(r));
        var req = new NovoPedidoRequest();
        req.setSlug("loja-teste");
        req.setModo("delivery");
        assertThatThrownBy(() -> service.criarPedido(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("apenas por retirada");
        verify(pedidos, never()).saveAndFlush(any());
    }
}
