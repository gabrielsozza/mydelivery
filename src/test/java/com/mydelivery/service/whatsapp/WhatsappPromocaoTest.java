package com.mydelivery.service.whatsapp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappInstanceRepository;

@ExtendWith(MockitoExtension.class)
class WhatsappPromocaoTest {
    @Mock UazapiClient evolutionClient;
    @Mock WhatsappInstanceRepository repo;
    @InjectMocks WhatsappService service;
    WhatsappInstance inst;
    final String tel = "5527999990000";
    @BeforeEach void preparar() {
        inst = new WhatsappInstance(); inst.setInstanceName(UUID.randomUUID().toString());
        inst.setStatus(WhatsappInstance.Status.CONECTADA); inst.setInstanceToken("teste");
        ReflectionTestUtils.setField(service,"rateLimitNumHora",8);
        ReflectionTestUtils.setField(service,"rateLimitNumDia",20);
    }
    @Test void confirmaChamadaAoProvedorSemConsumirLimiteGlobalDoBot() {
        assertThat(service.enviarPromocao(inst,tel,"Promoção")).isTrue();
        verify(evolutionClient).enviarTexto(inst.getInstanceName(),"teste",tel,"Promoção",0);
        assertThat(inst.getUltimaRespostaEnviadaEm()).isNotNull();
    }
    @Test void imagemEEnviadaComLegendaEmUmaMensagem() {
        String url = "https://res.cloudinary.com/teste/image/upload/promo.jpg";
        assertThat(service.enviarPromocao(inst,tel,"Olá Ana, promoção\nPARE",url)).isTrue();
        verify(evolutionClient).enviarMidia(inst.getInstanceName(),"teste",tel,url,"Olá Ana, promoção\nPARE",0);
        verify(evolutionClient,never()).enviarTexto(anyString(),anyString(),anyString(),anyString(),anyInt());
    }
    @Test void falhaDaImagemNaoEnviaTextoDuplicado() {
        when(evolutionClient.enviarMidia(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt())).thenThrow(new RuntimeException("Mídia falhou"));
        assertThatThrownBy(() -> service.enviarPromocao(inst,tel,"Promoção","https://res.cloudinary.com/teste/image/upload/promo.jpg")).hasMessage("Mídia falhou");
        verify(evolutionClient,never()).enviarTexto(anyString(),anyString(),anyString(),anyString(),anyInt());
    }
    @Test void erroNaoViraSucesso() {
        when(evolutionClient.enviarTexto(anyString(),anyString(),anyString(),anyString(),anyInt())).thenThrow(new RuntimeException("API falhou"));
        assertThatThrownBy(() -> service.enviarPromocao(inst,tel,"Promoção")).hasMessage("API falhou");
        assertThat(inst.getUltimaRespostaEnviadaEm()).isNull();
    }
    @Test void repeticaoNaoChamaProvedorDuasVezes() {
        assertThat(service.enviarPromocao(inst,tel,"Promoção")).isTrue();
        assertThat(service.enviarPromocao(inst,tel,"Promoção")).isFalse();
        verify(evolutionClient,times(1)).enviarTexto(anyString(),anyString(),anyString(),anyString(),anyInt());
    }
    @Test void preservaControlePorDestinatario() {
        ReflectionTestUtils.setField(service,"rateLimitNumHora",1);
        assertThat(service.enviarPromocao(inst,tel,"Promoção 1")).isTrue();
        assertThat(service.enviarPromocao(inst,tel,"Promoção 2")).isFalse();
        verify(evolutionClient,times(1)).enviarTexto(anyString(),anyString(),anyString(),anyString(),anyInt());
    }
    @Test void telefoneNacionalRecebeDdiNoTextoENaImagem() {
        service.enviarPromocao(inst,"(84) 99999-1234","Oferta A");
        verify(evolutionClient).enviarTexto(inst.getInstanceName(),"teste","5584999991234","Oferta A",0);
        service.enviarPromocao(inst,"84999991234","Oferta B","https://res.cloudinary.com/test/image/upload/a.jpg");
        verify(evolutionClient).enviarMidia(inst.getInstanceName(),"teste","5584999991234","https://res.cloudinary.com/test/image/upload/a.jpg","Oferta B",0);
    }
    @Test void ddd55NaoEConfundidoComDdiEFormatoInternacionalNaoDuplica() {
        service.enviarPromocao(inst,"55999991234","Oferta A");
        verify(evolutionClient).enviarTexto(inst.getInstanceName(),"teste","5555999991234","Oferta A",0);
        service.enviarPromocao(inst,"+55 (84) 3333-1234","Oferta B");
        verify(evolutionClient).enviarTexto(inst.getInstanceName(),"teste","558433331234","Oferta B",0);
    }
    @Test void telefoneInvalidoNaoVaiAoProvedor() {
        assertThatThrownBy(() -> service.enviarPromocao(inst,"1234","Oferta")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(evolutionClient);
    }
    @Test void formatosDiferentesNaoDuplicamMensagem() {
        assertThat(service.enviarPromocao(inst,"27999990000","Oferta")).isTrue();
        assertThat(service.enviarPromocao(inst,tel,"Oferta")).isFalse();
        verify(evolutionClient,times(1)).enviarTexto(anyString(),anyString(),anyString(),anyString(),anyInt());
    }
    @Test void desconectadoNaoEnvia() {
        inst.setStatus(WhatsappInstance.Status.DESCONECTADA);
        assertThat(service.enviarPromocao(inst,tel,"Promoção")).isFalse();
        verifyNoInteractions(evolutionClient);
    }
}
