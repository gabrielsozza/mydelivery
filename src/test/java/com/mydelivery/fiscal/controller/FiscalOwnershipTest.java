package com.mydelivery.fiscal.controller;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import com.mydelivery.repository.*;
import com.mydelivery.fiscal.repository.*;
import com.mydelivery.fiscal.config.FiscalConfig;
import com.mydelivery.fiscal.service.NfceEmissorService;
import com.mydelivery.fiscal.model.NotaFiscalEmitida;
import com.mydelivery.model.Restaurante;
@ExtendWith(MockitoExtension.class)
class FiscalOwnershipTest {
 @Mock PedidoRepository pedidoRepo; @Mock ProdutoRepository produtoRepo; @Mock RestauranteRepository restauranteRepo; @Mock NotaFiscalEmitidaRepository notaRepo; @Mock FiscalConfig fiscalConfig; @Mock NfceEmissorService emissor; @InjectMocks FiscalController controller;
 Restaurante r;
 @BeforeEach void setup(){r=new Restaurante();r.setId(9L);when(restauranteRepo.findByUsuarioEmail("loja")).thenReturn(Optional.of(r));}
 @Test void emissaoDeOutroRestauranteBloqueadaAntesDoGateway(){when(fiscalConfig.autorizadoParaRestaurante(r)).thenReturn(true);assertThat(controller.emitirNfce("loja",5L,null).getStatusCode().value()).isEqualTo(404);verifyNoInteractions(emissor);}
 @Test void cancelamentoDeOutraLojaBloqueadoAntesDoGateway(){when(fiscalConfig.autorizadoParaRestaurante(r)).thenReturn(true);var outra=new Restaurante();outra.setId(10L);when(notaRepo.findById(3L)).thenReturn(Optional.of(NotaFiscalEmitida.builder().restaurante(outra).build()));assertThat(controller.cancelarNota("loja",3L,Map.of(),null).getStatusCode().value()).isEqualTo(404);verifyNoInteractions(emissor);}
 @Test void impressaoConsultaSomenteNotaAutorizadaDoPedidoDaLoja(){when(pedidoRepo.existsByIdAndRestauranteId(5L,9L)).thenReturn(true);when(notaRepo.findFirstByRestauranteIdAndPedidoIdAndStatusOrderByIdDesc(9L,5L,NotaFiscalEmitida.Status.AUTORIZADA)).thenReturn(Optional.empty());assertThat(controller.dadosFiscaisDoPedido("loja",5L).getBody()).containsEntry("temNota",false);verifyNoInteractions(emissor,fiscalConfig);}
 @Test void impressaoDeOutraLojaNaoExpoeNota(){assertThat(controller.dadosFiscaisDoPedido("loja",5L).getStatusCode().value()).isEqualTo(404);verifyNoInteractions(notaRepo);}
}
