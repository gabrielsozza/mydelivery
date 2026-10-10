package com.mydelivery.campanhas;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mydelivery.model.*;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.UazapiClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.mydelivery.campanhas.CampaignService.*;

class CampaignSafetyTest {
    JdbcTemplate db;CampaignService service;CampaignDispatcher dispatcher;CampaignEvents events;CampaignPolicy policy;
    WhatsappInstanceRepository instances;UazapiClient transport;WhatsappInstance instance;
    final String phone="11987654321";
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");db=new JdbcTemplate(ds);
        for(String ddl:List.of(
            "CREATE TABLE restaurantes(id BIGINT PRIMARY KEY)",
            "CREATE TABLE clientes(id BIGINT PRIMARY KEY,restaurante_id BIGINT,nome VARCHAR(120),telefone VARCHAR(25))",
            "CREATE TABLE pedidos(id BIGINT PRIMARY KEY,restaurante_id BIGINT,cliente_id BIGINT,status VARCHAR(30),criado_em TIMESTAMP,pago BOOLEAN,total DECIMAL(10,2))",
            "CREATE TABLE pedido_itens(pedido_id BIGINT,produto_id BIGINT)",
            "CREATE TABLE pagamentos(pedido_id BIGINT,status VARCHAR(30))",
            "CREATE TABLE produtos(id BIGINT PRIMARY KEY,restaurante_id BIGINT,categoria_id BIGINT)",
            "CREATE TABLE mycash_config(restaurante_id BIGINT,ativo BOOLEAN)",
            "CREATE TABLE mycash_movimentos(restaurante_id BIGINT,telefone VARCHAR(20),valor DECIMAL(10,2))",
            "CREATE TABLE promo_campanhas(id BIGINT PRIMARY KEY,restaurante_id BIGINT,status VARCHAR(20))",
            "CREATE TABLE promo_campanha_envios(campanha_id BIGINT,telefone VARCHAR(20),sucesso BOOLEAN,enviado_em TIMESTAMP)",
            "CREATE TABLE promo_opt_outs(restaurante_id BIGINT,telefone VARCHAR(20),motivo VARCHAR(60),criado_em TIMESTAMP,UNIQUE(restaurante_id,telefone))",
            "CREATE TABLE whatsapp_instances(restaurante_id BIGINT,instance_name VARCHAR(80),phone VARCHAR(20),status VARCHAR(20))"))db.execute(ddl);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V18_1__campanhas_responsaveis.sql"),new ClassPathResource("db/migration/V18_2__campanhas_regras_centrais.sql")).execute(ds);
        db.update("INSERT INTO restaurantes VALUES(1),(2)");db.update("INSERT INTO clientes VALUES(1,1,'Ana',?),(2,2,'Outra',?)",phone,phone);
        db.update("INSERT INTO pedidos VALUES(1,1,1,'ENTREGUE',?,true,50),(2,2,2,'ENTREGUE',?,true,70)",now().minusDays(35),now().minusDays(40));
        db.update("INSERT INTO whatsapp_instances VALUES(1,'store-one','5511999999999','CONECTADA'),(2,'store-two','5521999999999','CONECTADA')");
        policy=new CampaignPolicy();service=new CampaignService(db,new DataSourceTransactionManager(ds),policy,new ObjectMapper());
        instances=mock(WhatsappInstanceRepository.class);transport=mock(UazapiClient.class);dispatcher=new CampaignDispatcher(service,instances,transport);events=new CampaignEvents(service);
        instance=WhatsappInstance.builder().restaurante(Restaurante.builder().id(1L).build()).instanceName("store-one").phone("5511999999999").instanceToken("test-token").status(WhatsappInstance.Status.CONECTADA).build();
        when(instances.findByRestauranteId(1L)).thenReturn(Optional.of(instance));when(transport.enviarTexto(anyString(),anyString(),anyString(),anyString(),eq(0))).thenReturn(Map.of("messageid","test-message"));
        service.config(1,false);db.update("UPDATE campanha_controles SET inicio='00:00',fim='23:59' WHERE restaurante_id=1");
    }
    Map<String,Object> body(){return new HashMap<>(Map.of("chave",UUID.randomUUID().toString(),"texto","Oferta da loja.","segmento","BASE","inativo_dias",30));}
    void consent(){service.consent(1,Map.of("telefone",phone,"evidencia","Autorizou ofertas na conversa de 18/09/2026; referência local 123."),"owner");}
    long create(){consent();return num(service.create(1,body()),"id");}
    long start(){long id=create();service.start(1,id,null);return id;}
    String state(long id){return db.queryForObject("SELECT status FROM campanhas_crm WHERE id=?",String.class,id);}
    String destination(){return db.queryForObject("SELECT status FROM campanha_destinos ORDER BY id LIMIT 1",String.class);}
    @Test void ownRelationshipCanBeQueuedWithoutRecordedConsent(){
        assertEquals(1,service.preview(1,body()).get("elegiveis"));
        long id=num(service.create(1,body()),"id");
        service.start(1,id,null);
        assertEquals(1L,num(service.detail(1,id),"pendentes"));
        assertEquals(1,service.preview(2,body()).get("elegiveis"));
    }
    @Test void authorizedImportedCustomersDoNotNeedOrdersInThisSystem(){db.update("DELETE FROM pedidos WHERE restaurante_id=1");consent();assertEquals(1,service.preview(1,body()).get("elegiveis"));}

    @Test void optOutCannotBeOverriddenByConsent(){consent();events.inbound(instance,"55"+phone+"@s.whatsapp.net","opt1","Não quero receber suas promoções, por favor.",false,false,Instant.now().getEpochSecond());assertTrue(service.optedOut(1,phone));assertThrows(RuntimeException.class,()->consent());assertEquals(0,service.preview(1,body()).get("elegiveis"));}
    @Test void naturalLanguageOptOutDoesNotConfuseOrderCancellation(){assertTrue(CampaignPolicy.optOut("PARE!"));assertTrue(CampaignPolicy.optOut("remover"));assertTrue(CampaignPolicy.optOut("Não me mande mais mensagens"));assertFalse(CampaignPolicy.optOut("Quero cancelar meu pedido"));}
    @Test void duplicateContactsAndCreateRequestsDoNotDuplicateQueue(){db.update("INSERT INTO clientes VALUES(3,1,'Duplicado',?)","55"+phone);consent();var b=body();long a=num(service.create(1,b),"id"),c=num(service.create(1,b),"id");assertEquals(a,c);service.start(1,a,null);service.start(1,a,null);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM campanha_destinos",Integer.class));}
    @Test void pausedAndCancelledDoNotSend(){long id=start();service.action(1,id,"pausar");assertNull(dispatcher.claim(1));service.action(1,id,"cancelar");assertNull(dispatcher.claim(1));assertEquals("CANCELADO",destination());verifyNoInteractions(transport);}
    @Test void twoWorkersCannotClaimSameStore()throws Exception{start();var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->dispatcher.claim(1));var b=pool.submit(()->dispatcher.claim(1));assertEquals(1,(a.get(10,TimeUnit.SECONDS)!=null?1:0)+(b.get(10,TimeUnit.SECONDS)!=null?1:0));}finally{pool.shutdownNow();}}
    @Test void twoCampaignsShareStorePacing(){start();long second=num(service.create(1,body()),"id");service.start(1,second,null);dispatcher.send(dispatcher.claim(1));assertNull(dispatcher.claim(1));verify(transport,times(1)).enviarTexto(anyString(),anyString(),anyString(),anyString(),eq(0));}

    @Test void explicitRateLimitRetriesWithBackoffThenStops(){start();var c=dispatcher.claim(1);var err=new RestClientResponseException("rate",429,"limit",null,new byte[0],null);dispatcher.failure(c,err);assertEquals("PENDENTE",destination());assertNull(dispatcher.claim(1));db.update("UPDATE campanha_controles SET proximo_envio=NULL");db.update("UPDATE campanha_destinos SET proxima_tentativa=NULL,tentativas=2");c=dispatcher.claim(1);dispatcher.failure(c,err);assertEquals("FALHA",destination());assertEquals("PAUSADA",state(c.campaign()));}
    @Test void permanentErrorDoesNotRetry(){long id=start();dispatcher.failure(dispatcher.claim(1),new RestClientResponseException("invalid",400,"bad",null,"number not on WhatsApp".getBytes(),null));assertEquals("FALHA",destination());assertEquals("PAUSADA",state(id));assertTrue(yes(db.queryForMap("SELECT restrito FROM campanha_contatos WHERE restaurante_id=1").get("restrito")));}
    @Test void timeoutIsUncertainAndNeverRetried(){long id=start();dispatcher.failure(dispatcher.claim(1),new RuntimeException("timeout"));assertEquals("INCERTO",destination());assertThrows(RuntimeException.class,()->service.start(1,id,null));assertNull(dispatcher.claim(1));new CampaignChannelGuard(db).checkReconnect("store-one");}
    @Test void channelRestrictionStopsWithoutReconnectOrFallback(){start();dispatcher.failure(dispatcher.claim(1),new RestClientResponseException("denied",403,"forbidden",null,new byte[0],null));assertNull(dispatcher.claim(1));assertThrows(IllegalStateException.class,()->new CampaignChannelGuard(db).checkReconnect("store-one"));assertDoesNotThrow(()->new CampaignChannelGuard(db).checkReconnect("store-two"));verifyNoInteractions(transport);}
    @Test void crashAfterClaimDoesNotResend(){start();dispatcher.claim(1);db.update("UPDATE campanha_destinos SET tentativa_em=?",now().minusMinutes(5));assertNull(dispatcher.claim(1));assertEquals("INCERTO",destination());verifyNoInteractions(transport);}
    @Test void changingSessionCannotContinue(){long id=start();db.update("UPDATE whatsapp_instances SET phone='5511888888888' WHERE restaurante_id=1");assertNull(dispatcher.claim(1));assertEquals("PAUSADA",state(id));verifyNoInteractions(transport);}
    @Test void checksOptOutAgainAfterEnqueue(){start();db.update("INSERT INTO promo_opt_outs VALUES(1,?,'PARE',?)",phone,now());assertNull(dispatcher.claim(1));assertEquals("IGNORADO",destination());}
    @Test void previousCampaignDoesNotExcludeCustomerFromNewCampaign(){consent();db.update("INSERT INTO promo_campanhas VALUES(7,1,'CONCLUIDA')");db.update("INSERT INTO promo_campanha_envios VALUES(7,?,true,?)","55"+phone,now().minusDays(1));assertEquals(1,service.preview(1,body()).get("elegiveis"));}
    @Test void queueOrdersReturningThenSingleThenZeroOrderCustomers(){db.update("INSERT INTO clientes VALUES(3,1,'Recorrente','21987654321'),(4,1,'Importado','31987654321')");db.update("INSERT INTO pedidos VALUES(3,1,3,'ENTREGUE',?,true,50),(4,1,3,'ENTREGUE',?,true,50)",now(),now());consent();for(String tel:List.of("21987654321","31987654321"))service.consent(1,Map.of("telefone",tel,"evidencia","Autorização confirmada pelo responsável da base de clientes."),"owner");long id=num(service.create(1,body()),"id");service.start(1,id,null);assertEquals(3L,num(service.detail(1,id),"pendentes"));for(String tel:List.of("21987654321",phone,"31987654321")){db.update("UPDATE campanha_controles SET proximo_envio=NULL");var claim=dispatcher.claim(1);assertNotNull(claim);assertEquals(tel,claim.phone());dispatcher.send(claim);}assertEquals(0L,num(service.detail(1,id),"pendentes"));assertEquals(3L,num(service.detail(1,id),"enviados"));}

    @Test void fixedIntervalAndDailyCap(){start();for(int i=0;i<100;i++)service.audit(1,"TENTATIVA","fixture");assertNull(dispatcher.claim(1));assertTrue(CampaignPolicy.inWindow(LocalTime.of(9,0),"09:00","20:00"));assertFalse(CampaignPolicy.inWindow(LocalTime.of(20,0),"09:00","20:00"));assertThrows(RuntimeException.class,()->service.saveSettings(1,Map.of("intervalo_segundos",29)));}
    @Test void recordsAcceptanceNotDelivery(){long id=start();dispatcher.send(dispatcher.claim(1));assertEquals("ACEITO",destination());assertEquals(1L,((Number)service.detail(1,id).get("enviados")).longValue());assertEquals(0L,((Number)service.detail(1,id).get("entregues")).longValue());}
    @Test void botRepliesAndImmediateRepliesExcludedAndDuplicateEventsIgnored(){long id=start();dispatcher.send(dispatcher.claim(1));db.update("UPDATE campanha_destinos SET aceito_em=?",now().minusSeconds(10));long ts=Instant.now().getEpochSecond();events.inbound(instance,phone,"bot","Mensagem automática: agradecemos o contato",false,false,ts);assertEquals(0L,((Number)service.detail(1,id).get("respostasProvaveis")).longValue());events.inbound(instance,phone,"human","Quanto custa?",false,false,ts);events.inbound(instance,phone,"human","Quanto custa?",false,false,ts);assertEquals(1L,((Number)service.detail(1,id).get("respostasProvaveis")).longValue());assertFalse(CampaignPolicy.possibleHuman("Oi",1,false));}
    @Test void paidOrdersAreAttributedOnceWithinWindowAndCancellationRemovesRevenue(){long id=start();dispatcher.send(dispatcher.claim(1));db.update("UPDATE campanha_destinos SET aceito_em=?",now().minusDays(1));db.update("INSERT INTO pedidos VALUES(10,1,1,'ENTREGUE',?,true,99),(11,2,2,'ENTREGUE',?,true,88)",now(),now());events.attributeNow();events.attributeNow();assertEquals(1L,((Number)service.detail(1,id).get("pedidosAtribuidos")).longValue());assertEquals(99.0,((Number)service.detail(1,id).get("receitaAtribuida")).doubleValue());db.update("UPDATE pedidos SET status='CANCELADO' WHERE id=10");assertEquals(0L,((Number)service.detail(1,id).get("pedidosAtribuidos")).longValue());}
    @Test void myCashAndInactivitySegmentUseOwnWallet(){consent();db.update("INSERT INTO mycash_config VALUES(1,true),(2,true)");db.update("INSERT INTO mycash_movimentos VALUES(2,?,10)",phone);var b=body();b.put("segmento","MYCASH_INATIVOS");assertEquals(0,service.preview(1,b).get("elegiveis"));db.update("INSERT INTO mycash_movimentos VALUES(1,?,5)",phone);assertEquals(1,service.preview(1,b).get("elegiveis"));}
    @Test void crossStoreCampaignAccessAndConsentFail(){long id=create();assertThrows(RuntimeException.class,()->service.detail(2,id));assertThrows(RuntimeException.class,()->service.action(2,id,"cancelar"));assertThrows(RuntimeException.class,()->service.consent(2,Map.of("telefone","21987654321","evidencia","suposta autorização longa inexistente"),"owner"));}
    @Test void increasedNegativeSignalsPauseCampaignsButNotNormalBot(){policy.setSinaisParaPausa(1);long id=start();dispatcher.send(dispatcher.claim(1));db.update("UPDATE campanha_destinos SET aceito_em=?",now().minusSeconds(10));events.inbound(instance,phone,"neg","Não tenho interesse",false,false,Instant.now().getEpochSecond());assertEquals("PAUSADA",state(id));assertNull(dispatcher.claim(1));assertDoesNotThrow(()->new CampaignChannelGuard(db).checkReconnect("store-one"));}
    @Test void documentedProviderRestrictionsAreUsedBeforeSending(){start();when(transport.limitesCampanha(anyString(),anyString())).thenReturn(Map.of("can_send_new_messages",false,"reachout_timelock",Map.of("active",true)));dispatcher.send(dispatcher.claim(1));assertEquals("PENDENTE",destination());assertNull(dispatcher.claim(1));verify(transport,never()).enviarTexto(anyString(),anyString(),anyString(),anyString(),anyInt());}
    @Test void provider463RestrictionDoesNotTriggerReconnect(){start();dispatcher.failure(dispatcher.claim(1),new RestClientResponseException("provider",500,"error",null,"{\"provider_code\":463,\"error_key\":\"WHATSAPP_REACHOUT_TIMELOCK\"}".getBytes(),null));assertEquals("FALHA",destination());assertThrows(IllegalStateException.class,()->new CampaignChannelGuard(db).checkReconnect("store-one"));}
    @Test void deliveredReceiptIsIdempotentAndTenantScoped(){long id=start();dispatcher.send(dispatcher.claim(1));var other=WhatsappInstance.builder().restaurante(Restaurante.builder().id(2L).build()).build();events.receipt(other,Map.of("messageid","test-message","status","Delivered"));assertEquals(0L,((Number)service.detail(1,id).get("entregues")).longValue());events.receipt(instance,Map.of("messageid","test-message","status","Delivered"));events.receipt(instance,Map.of("messageid","test-message","status","Read"));assertEquals(1L,((Number)service.detail(1,id).get("entregues")).longValue());assertEquals(true,service.detail(1,id).get("entregaDisponivel"));}
    @Test void failureReceiptStopsQueue(){long id=start();dispatcher.send(dispatcher.claim(1));events.receipt(instance,Map.of("messageid","test-message","status","Failed"));assertEquals("PAUSADA",state(id));assertEquals("FALHA",destination());}
    @Test void providerQuotaSignalsAreNotInvented(){assertFalse(CampaignDispatcher.restricted(Map.of("new_chat_message_capping",Map.of("available",true,"total_quota",0,"used_quota",0))));assertTrue(CampaignDispatcher.restricted(Map.of("new_chat_message_capping",Map.of("total_quota",10,"used_quota",10))));}
    @Test void mediaUsesSameSessionAndNoTypingDelay(){consent();var b=body();b.put("imagemUrl","https://res.cloudinary.com/test/image/upload/offer.webp");long id=num(service.create(1,b),"id");service.start(1,id,null);when(transport.enviarMidia(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt())).thenReturn(Map.of("messageid","media-1","error",""));dispatcher.send(dispatcher.claim(1));assertEquals("ACEITO",destination());verify(transport).enviarMidia(eq("store-one"),eq("test-token"),eq("55"+phone),contains("f_jpg"),eq("Olá Ana,\n\nOferta da loja."),eq(0));}
    @Test void futureScheduleDoesNotSend(){long id=create();service.start(1,id,now().plusDays(1).toString());assertEquals("AGENDADA",state(id));assertNull(dispatcher.claim(1));}
    @Test void refundDoesNotCountAsRevenue(){long id=start();dispatcher.send(dispatcher.claim(1));db.update("UPDATE campanha_destinos SET aceito_em=?",now().minusDays(1));db.update("INSERT INTO pedidos VALUES(10,1,1,'ENTREGUE',?,true,99)",now());events.attributeNow();db.update("INSERT INTO pagamentos VALUES(10,'CANCELADO')");assertEquals(0L,((Number)service.detail(1,id).get("pedidosAtribuidos")).longValue());}

    @Test void privilegedReviewDoesNotResendUncertainRecipient(){long id=start();dispatcher.failure(dispatcher.claim(1),new RuntimeException("timeout"));var review=new CampaignReviewController(service,instances,transport);when(transport.limitesCampanha(anyString(),anyString())).thenReturn(Map.of("can_send_new_messages",true));review.review("admin",1,Map.of("evidencia","Conferência técnica concluída, sem reenviar o destino incerto."));assertEquals("PAUSADA",state(id));assertEquals("INCERTO",destination());assertDoesNotThrow(()->service.start(1,id,null));assertNull(dispatcher.claim(1));verify(transport,never()).enviarTexto(anyString(),anyString(),anyString(),anyString(),anyInt());}
    @Test void changedPayloadCannotReuseIdempotencyKey(){consent();var b=body();service.create(1,b);b.put("texto","Outra oferta.");assertThrows(RuntimeException.class,()->service.create(1,b));}
    @Test void lastCheckStopsOptoutReceivedDuringDiagnostics(){start();var c=dispatcher.claim(1);when(transport.limitesCampanha(anyString(),anyString())).thenAnswer(x->{db.update("INSERT INTO promo_opt_outs VALUES(1,?,'PARE',?)",phone,now());return Map.of("can_send_new_messages",true);});dispatcher.send(c);assertEquals("IGNORADO",destination());verify(transport,never()).enviarTexto(anyString(),anyString(),anyString(),anyString(),anyInt());}
    @Test void ownersCannotChangeLimits(){var err=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.saveSettings(1,Map.of("limite_dia",999,"fim","23:59")));assertEquals(403,err.getStatusCode().value());assertEquals(100L,num(service.config(1,false),"limite_dia"));}
    @Test void pacingRotatesAndPersistsAcrossWorkers(){start();var before=now();assertNotNull(dispatcher.claim(1));var cfg=service.config(1,false);assertEquals(1L,num(cfg,"ritmo_passos"));assertTrue(time(cfg.get("proximo_envio")).isAfter(before.plusSeconds(29)));assertNull(new CampaignDispatcher(service,instances,transport).claim(1));for(int i=0;i<9;i++)assertEquals(30+(i%3)*30,policy.intervalForStep(i));}
    @Test void newStoresUseNineToTwentyThreeWindow(){var cfg=service.config(2,false);assertEquals("09:00",str(cfg,"inicio"));assertEquals("23:00",str(cfg,"fim"));assertTrue(CampaignPolicy.inWindow(LocalTime.of(22,59),str(cfg,"inicio"),str(cfg,"fim")));assertFalse(CampaignPolicy.inWindow(LocalTime.of(23,0),str(cfg,"inicio"),str(cfg,"fim")));}


    @Test void ordinaryPauseCannotClearRiskBlock(){service.tx.executeWithoutResult(t->{service.config(1,true);service.stop(1,"Restrição original",true);service.stop(1,"Falha comum",false);});assertTrue(yes(service.config(1,false).get("bloqueado")));assertEquals("Restrição original",service.config(1,false).get("motivo"));}

}
