package com.mydelivery.service.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import com.mydelivery.config.EvolutionProperties;
import com.mydelivery.model.*;
import com.mydelivery.repository.*;

class WhatsappBotLogoTest {
    JdbcTemplate db;
    DriverManagerDataSource ds;
    UazapiClient transport;
    WhatsappService service;
    WhatsappInstance inst;
    static final String PHONE="5527999991234", LOGO="https://example.com/logo.png";
    @BeforeEach void setup() {
        ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        db=new JdbcTemplate(ds);
        db.execute("CREATE TABLE restaurantes(id BIGINT PRIMARY KEY)");
        db.update("INSERT INTO restaurantes VALUES (58),(48)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V18_4__bot_logo_primeiro_contato.sql")).execute(ds);
        transport=mock(UazapiClient.class); service=newService(); inst=instance(58L);
    }
    WhatsappService newService() {
        return new WhatsappService(mock(WhatsappInstanceRepository.class),mock(WhatsappHealthLogRepository.class),
            mock(WhatsappDesconexaoLogRepository.class),db,transport,new EvolutionProperties(),new DataSourceTransactionManager(ds));
    }
    WhatsappInstance instance(long id) {
        Restaurante r=new Restaurante();r.setId(id);r.setLogoUrl(LOGO);r.setNome("Loja");r.setSlug("loja");r.setAberto(true);
        WhatsappInstance i=new WhatsappInstance();i.setRestaurante(r);i.setInstanceName(UUID.randomUUID().toString());i.setInstanceToken("test");i.setStatus(WhatsappInstance.Status.CONECTADA);i.setBotAtivo(true);return i;
    }
    @Test void primeiraRespostaTemLogoDemaisTemTextoMesmoAposReinicio() {
        service.enviarRespostaBot(inst,PHONE,"Olá https://example.com/menu",0);
        service.enviarRespostaBot(inst,PHONE,"Veja o cardápio https://example.com/menu",0);
        newService().enviarRespostaBot(inst,PHONE,"Olá de novo https://example.com/menu",0);
        verify(transport,times(1)).enviarMidia(anyString(),eq("test"),eq(PHONE),eq(LOGO),anyString(),eq(0));
        verify(transport,times(2)).enviarTexto(anyString(),eq("test"),eq(PHONE),anyString(),eq(0));
    }
    @Test void clientesELojasSaoIndependentes() {
        assertThat(service.reservarPrimeiraRespostaBot(inst,PHONE)).isTrue();
        assertThat(service.reservarPrimeiraRespostaBot(inst,"5527999995678")).isTrue();
        assertThat(service.reservarPrimeiraRespostaBot(instance(48L),PHONE)).isTrue();
        assertThat(service.reservarPrimeiraRespostaBot(inst,PHONE)).isFalse();
    }
    @Test void mesmoTelefoneComOuSemNonoDigitoOuJidNaoRepete() {
        assertThat(service.reservarPrimeiraRespostaBot(inst,"(27) 99999-1234")).isTrue();
        assertThat(service.reservarPrimeiraRespostaBot(inst,"552799991234@s.whatsapp.net")).isFalse();
    }
    @Test void concorrenciaReservaUmaUnicaLogo() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> calls=new ArrayList<>();
            for(int n=0;n<16;n++)calls.add(()->newService().reservarPrimeiraRespostaBot(inst,PHONE));
            int first=0;for(var f:pool.invokeAll(calls))if(f.get())first++;
            assertThat(first).isEqualTo(1);
        }
    }
    @Test void erroDeBancoContinuaAtendimentoEmTexto() {
        db.execute("DROP TABLE whatsapp_bot_primeiro_contato");
        service.enviarRespostaBot(inst,PHONE,"Resposta",0);
        verify(transport).enviarTexto(anyString(),anyString(),eq(PHONE),eq("Resposta"),eq(0));
        verify(transport,never()).enviarMidia(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt());
    }
    @Test void timeoutDeMidiaNaoRepeteFotoOuEnviaFallbackDuplicado() {
        when(transport.enviarMidia(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt())).thenThrow(new RuntimeException("timeout"));
        service.enviarRespostaBot(inst,PHONE,"Primeira",0);
        verify(transport,never()).enviarTexto(anyString(),anyString(),anyString(),anyString(),anyInt());
        newService().enviarRespostaBot(inst,PHONE,"Próxima",0);
        verify(transport,times(1)).enviarMidia(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt());
        verify(transport).enviarTexto(anyString(),anyString(),eq(PHONE),eq("Próxima"),eq(0));
    }
    @Test void notificaPedidoSemConsumirPrimeiraRespostaEBotDesconectadoNaoReserva() {
        service.enviarMensagem(inst,PHONE,"Pedido confirmado",0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM whatsapp_bot_primeiro_contato",Integer.class)).isZero();
        inst.setStatus(WhatsappInstance.Status.DESCONECTADA);
        service.enviarRespostaBot(inst,PHONE,"Olá",0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM whatsapp_bot_primeiro_contato",Integer.class)).isZero();
    }
    @Test void semLogoSegueSomenteTexto() {
        inst.getRestaurante().setLogoUrl(null);
        service.enviarRespostaBot(inst,PHONE,"Olá",0);
        verify(transport).enviarTexto(anyString(),anyString(),eq(PHONE),eq("Olá"),eq(0));
    }
    @Test void saudacaoDoBotUsaNovoFluxoSemAnexarImagemNoTexto() {
        WhatsappService sender=mock(WhatsappService.class);
        ConfiguracaoRestauranteRepository config=mock(ConfiguracaoRestauranteRepository.class);
        when(config.findByRestauranteId(58L)).thenReturn(Optional.empty());
        var bot=new WhatsappBotService(sender,config,new EvolutionProperties());
        bot.processar(inst,PHONE,"oi");
        verify(sender).enviarRespostaBot(eq(inst),eq(PHONE),argThat(s->!s.startsWith("IMG::")&&s.contains("mydeliveryfood.com.br/loja")),anyInt());
        verify(sender,never()).enviarMensagem(any(),anyString(),anyString(),anyInt());
    }
    @Test void falhaNaRepeticaoNaoInterrompeRespostaNormalDoBot() {
        WhatsappService sender = mock(WhatsappService.class);
        ConfiguracaoRestauranteRepository config = mock(ConfiguracaoRestauranteRepository.class);
        WhatsappRepeticaoService repeticao = mock(WhatsappRepeticaoService.class);
        when(config.findByRestauranteId(58L)).thenReturn(Optional.empty());
        when(repeticao.processar(any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("falha na consulta de pedidos"));
        var bot = new WhatsappBotService(sender, config, new EvolutionProperties());
        ReflectionTestUtils.setField(bot, "repeticaoService", repeticao);

        bot.processar(inst, PHONE, "oi");

        verify(sender).enviarRespostaBot(eq(inst), eq(PHONE),
                argThat(s -> s.contains("mydeliveryfood.com.br/loja")), anyInt());
    }
    @Test void mensagemInicialPersonalizadaTemPrioridadeNoPrimeiroContato() {
        WhatsappService sender=mock(WhatsappService.class);
        ConfiguracaoRestauranteRepository config=mock(ConfiguracaoRestauranteRepository.class);
        when(config.findByRestauranteId(58L)).thenReturn(Optional.empty());
        inst.getRestaurante().setWhatsappMensagemInicial("Olá! Bem-vindo à Loja. Como podemos ajudar?");

        var bot=new WhatsappBotService(sender,config,new EvolutionProperties());
        bot.processar(inst,PHONE,"quero ver o cardápio");

        verify(sender).enviarRespostaBot(eq(inst),eq(PHONE),
                eq("Olá! Bem-vindo à Loja. Como podemos ajudar?\n\nVocê pode pedir aqui: https://mydeliveryfood.com.br/loja"),anyInt());
    }

    @Test void avisoDeSomenteRetiradaAcompanhaLinkApenasEnquantoAtivo() {
        WhatsappService sender = mock(WhatsappService.class);
        ConfiguracaoRestauranteRepository config = mock(ConfiguracaoRestauranteRepository.class);
        when(config.findByRestauranteId(58L)).thenReturn(Optional.empty());
        inst.getRestaurante().setSomenteRetiradaAtivo(true);
        var bot = new WhatsappBotService(sender, config, new EvolutionProperties());
        bot.processar(inst, PHONE, "oi");
        verify(sender).enviarRespostaBot(eq(inst), eq(PHONE),
                argThat(s -> s.contains("https://mydeliveryfood.com.br/loja")
                        && s.contains("Hoje estamos atendendo apenas por retirada")), anyInt());

        inst.getRestaurante().setSomenteRetiradaAtivo(false);
        bot.processar(inst, "5527999999876", "oi");
        verify(sender).enviarRespostaBot(eq(inst), eq("5527999999876"),
                argThat(s -> s.contains("https://mydeliveryfood.com.br/loja")
                        && !s.contains("Hoje estamos atendendo apenas por retirada")), anyInt());
    }
}
