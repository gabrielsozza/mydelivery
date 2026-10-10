package com.mydelivery.service.whatsapp;
import com.mydelivery.config.EvolutionProperties;
import com.mydelivery.model.*;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.mockito.Mockito.*;
class WhatsappAtendimentoAlertaTest {
    @Test void pedidoHumanoNaPrimeiraMensagemGeraAlertaMesmoComSaudacaoPersonalizada() {
        var sender=mock(WhatsappService.class);var repo=mock(ConfiguracaoRestauranteRepository.class);
        var alerts=mock(AtendimentoManualService.class);
        var loja=new Restaurante();loja.setId(10L);loja.setNome("Loja");loja.setSlug("loja");loja.setAberto(true);loja.setWhatsappMensagemInicial("Bem-vindo ao nosso cardápio");
        var cfg=new ConfiguracaoRestaurante();cfg.setRestaurante(loja);
        when(repo.findByRestauranteId(10L)).thenReturn(Optional.of(cfg));
        var inst=new WhatsappInstance();inst.setRestaurante(loja);inst.setInstanceName("test");inst.setStatus(WhatsappInstance.Status.CONECTADA);inst.setBotAtivo(true);
        var bot=new WhatsappBotService(sender,repo,new EvolutionProperties());
        ReflectionTestUtils.setField(bot,"atendimentoManualService",alerts);
        bot.processar(inst,"5511912345678","quero falar com atendente");
        bot.processar(inst,"5511912345678","atendente");
        verify(alerts,times(1)).registrar(loja,"5511912345678");
        verify(sender,times(1)).enviarRespostaBot(eq(inst),eq("5511912345678"),contains("atendimento manual"),anyInt());
    }
}
