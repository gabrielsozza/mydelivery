package com.mydelivery.controller;
import com.mydelivery.service.EntregaService;
import com.mydelivery.repository.RestauranteRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class LocalizarLojaTest {
    EntregaService service=mock(EntregaService.class);
    EntregaController controller=new EntregaController(service,mock(RestauranteRepository.class));
    EntregaService.Localizacao local(String bairro,String cep,String cidade,String numero) {
        return new EntregaService.Localizacao(-20.144809,-40.2590467,numero.isEmpty()?"rua":"imovel",List.of(bairro),cidade,"BR-ES",numero,0.1,"Rua Rio Jucú",cep);
    }
    @Test void naoTrocaEldoradoPorAndreCarloniNaBuscaSemBairro() {
        when(service.localizar(anyString(),isNull(),isNull())).thenReturn(List.of(),List.of(local("André Carloni","29161-851","Serra","")));
        assertThat(controller.localizarLoja("Rua Rio Jucu","220","Eldorado","Serra","ES","29169-220").getStatusCode().value()).isEqualTo(404);
    }
    @Test void variacaoDeAcentoEApostrofoNaoDescartaBairroCorreto() {
        when(service.localizar(anyString(),isNull(),isNull())).thenReturn(List.of(local("El’dourado","29169-220","Serra","220")));
        var result=controller.localizarLoja("Rua Rio Jucu","220","Eldorado","Serra","ES","29169220");
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).containsEntry("precisao","imovel");
    }
    @Test void mesmoBairroComCepCidadeOuNumeroIncompatívelNaoMovePino() {
        for(var candidato:List.of(local("Eldorado","29161-851","Serra","220"),local("Eldorado","29169220","Vitória","220"),local("Eldorado","29169220","Serra","99"))) {
            when(service.localizar(anyString(),isNull(),isNull())).thenReturn(List.of(candidato));
            assertThat(controller.localizarLoja("Rua Rio Jucu","220","Eldorado","Serra","ES","29169220").getStatusCode().value()).isEqualTo(404);
        }
    }
    @Test void usaRuaAproximadaSomenteDepoisDeBuscaCompletaFalhar() {
        var rua = new EntregaService.Localizacao(-22.8815123,-43.5649233,"rua",List.of("Campo Grande"),"Rio de Janeiro","RJ","",Double.POSITIVE_INFINITY,"Rua Maestro Joaquim Naegele","23087-005");
        when(service.localizarRuaParaConferencia("Rua Maestro Joaquim Maegele","Campo Grande","Rio de Janeiro","RJ")).thenReturn(List.of(rua));
        var result=controller.localizarLoja("Rua Maestro Joaquim Maegele","179","Campo Grande","Rio de Janeiro","RJ","23090720");
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).containsEntry("precisao","rua");
        verify(service,times(3)).localizar(anyString(),isNull(),isNull());
    }
    @Test void ruasParecidasDistantesExigemResolverAmbiguidade() {
        var a=local("Eldorado","","Serra","");
        var b=new EntregaService.Localizacao(-20.2,-40.25,"rua",List.of("Eldorado"),"Serra","ES","",1,"Rua Rio Jucu","");
        when(service.localizarRuaParaConferencia(anyString(),anyString(),anyString(),anyString())).thenReturn(List.of(a,b));
        assertThat(controller.localizarLoja("Rua Rio Jucu","220","Eldorado","Serra","ES","29169220").getStatusCode().value()).isEqualTo(422);
    }
}
