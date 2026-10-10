package com.mydelivery.crm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mydelivery.model.*;
import com.mydelivery.repository.*;
import com.mydelivery.service.whatsapp.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.mydelivery.crm.CrmStore.*;

@SpringJUnitConfig(CrmIntegrationTest.Config.class)
class CrmIntegrationTest {
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean static org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor translation() { return new org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor(); }
        @Bean DataSource dataSource() { return new DriverManagerDataSource("jdbc:h2:mem:crm;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean CrmStore store(DataSource ds) { return spy(new CrmStore(new JdbcTemplate(ds),new ObjectMapper().findAndRegisterModules())); }
        @Bean CrmApproachService approaches() throws Exception { return new CrmApproachService(new ObjectMapper()); }
        @Bean RestauranteRepository restaurants() { return mock(RestauranteRepository.class); }
        @Bean WhatsappInstanceRepository instances() { return mock(WhatsappInstanceRepository.class); }
        @Bean UazapiClient provider() { return mock(UazapiClient.class); }
        @Bean WhatsappService whatsapp() { return mock(WhatsappService.class); }
        @Bean CrmOutboundPolicy outboundPolicy() { return new CrmOutboundPolicy(false); }
        @Bean CrmService service(CrmStore d,CrmApproachService a,RestauranteRepository r) { return new CrmService(d,a,r); }
        @Bean CrmDispatchService dispatch(CrmStore d,CrmService c,WhatsappInstanceRepository r,UazapiClient p,WhatsappService w,CrmOutboundPolicy policy) { return new CrmDispatchService(d,c,r,p,w,policy); }
        @Bean CrmInboundService inbound(CrmStore d,CrmService c) { return new CrmInboundService(d,c); }
        @Bean CrmCommercialWhatsappService commercialWhatsapp(CrmStore d,WhatsappInstanceRepository r,UazapiClient p,CrmOutboundPolicy policy) { return new CrmCommercialWhatsappService(d,r,p,policy); }
        @Bean CrmRegistrationService registrations(CrmStore d,CrmService c) { return new CrmRegistrationService(d,c); }
    }
    @Autowired DataSource source;
    @Autowired CrmStore db;
    @Autowired CrmService crm;
    @Autowired CrmDispatchService dispatch;
    @Autowired CrmInboundService inbound;
    @Autowired CrmCommercialWhatsappService commercialWhatsapp;
    @Autowired CrmRegistrationService registrations;
    @Autowired WhatsappInstanceRepository instances;
    @Autowired RestauranteRepository restaurants;
    @Autowired UazapiClient provider;
    @Autowired WhatsappService whatsapp;
    LocalDateTime now=LocalDateTime.of(2026,9,18,10,0);
    WhatsappInstance inst;
    final String phone="5527991234567";
    @BeforeEach void setup() {
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(db), "Repository proxy must match production");
        reset(provider,instances,restaurants,whatsapp);db.jdbc().execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V17__crm_comercial.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20__crm_equipe_comercial.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V30__crm_commercial_whatsapp.sql")).execute(source);
        doReturn(now).when((CrmStore)org.springframework.test.util.AopTestUtils.getUltimateTargetObject(db)).now();inst=instance(21L);when(instances.findByRestauranteId(21L)).thenReturn(Optional.of(inst));
        when(provider.enviarTexto(anyString(),anyString(),anyString(),anyString(),eq(0))).thenReturn(Map.of("id","provider-123","success",true));
    }
    static WhatsappInstance instance(long id) { return WhatsappInstance.builder().id(id).restaurante(Restaurante.builder().id(id).build()).instanceName("session-"+id).instanceToken("test-token").status(WhatsappInstance.Status.CONECTADA).desconectadoManualmente(false).build(); }
    long create(String name,String p) { return number(crm.create(Map.of("nome",name,"whatsapp",p,"cidade","Vitória","estado","ES"),"admin").get("id")); }
    long ready(String name,String p) { long id=create(name,p);crm.prepare(id,"","admin");return id; }
    long enqueue(long id) { return number(dispatch.enqueue(id,"INITIAL",UUID.randomUUID().toString(),number(db.lead(id).get("version")),"admin").get("id")); }
    @Test void analystCanPersonalizeMessageAndRecordCallOnlyForAssignedLead() {
        long id=number(crm.createForCollaborator(Map.of("nome","Loja de teste","whatsapp",phone),7,"admin").get("id"));
        long version=number(db.lead(id).get("version"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                ()->crm.commercialMessage(id,8,"Mensagem indevida",version,"comercial:8"));
        var saved=crm.commercialMessage(id,7,"Olá, posso explicar o teste?",version,"comercial:7");
        assertEquals("Olá, posso explicar o teste?",saved.get("prepared_message"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                ()->crm.commercialMessage(id,7,"Texto antigo",version,"comercial:7"));
        long current=number(db.lead(id).get("version"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                ()->crm.commercialCall(id,8,"PEDIU_RETORNO","Conversei com o responsável","","Enviar demonstração","2026-09-19T10:00",current,"comercial:8"));
        var result=crm.commercialCall(id,7,"PEDIU_RETORNO","Conversei com o responsável","Usa concorrente","Enviar demonstração","2026-09-19T10:00",current,"comercial:7");
        assertEquals("Enviar demonstração",((Map<?,?>)result.get("profile")).get("proximaAcao"));
        assertTrue(String.valueOf(result.get("next_action_at")).startsWith("2026-09-19T10:00"));
        assertEquals("LIGACAO_REGISTRADA",((Map<?,?>)((List<?>)result.get("timeline")).getFirst()).get("type"));
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_interactions WHERE lead_id=? AND type='LIGACAO_REGISTRADA'",Integer.class,id));
    }
    @Test void whatsappInboxFollowsLiveCrmStageAndKeepsAnalystsSeparate() {
        long first=number(crm.createForCollaborator(Map.of("nome","Loja A","whatsapp","5527997654301"),7,"admin").get("id"));
        long second=number(crm.createForCollaborator(Map.of("nome","Loja B","whatsapp","5527997654302"),8,"admin").get("id"));
        assertEquals("SENT",commercialWhatsapp.send(7,first,"Olá, Loja A",UUID.randomUUID().toString()).get("status"));
        assertEquals("SENT",commercialWhatsapp.send(8,second,"Olá, Loja B",UUID.randomUUID().toString()).get("status"));
        assertEquals(1,((List<?>)commercialWhatsapp.inbox(7,"NOVO_LEAD","").get("conversations")).size());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->commercialWhatsapp.conversation(7,second,0));
        crm.stage(first,"INTERESSADO","",number(db.lead(first).get("version")),"comercial:7");
        assertTrue(((List<?>)commercialWhatsapp.inbox(7,"NOVO_LEAD","").get("conversations")).isEmpty());
        assertEquals(1,((List<?>)commercialWhatsapp.inbox(7,"INTERESSADO","").get("conversations")).size());
        assertEquals(1,((List<?>)commercialWhatsapp.inbox(8,"NOVO_LEAD","").get("conversations")).size());
    }
    @Test void rejectedWhatsappNumberIsReportedWithoutClaimingAnUncertainSend() {
        long leadId=number(crm.createForCollaborator(Map.of("nome","Loja sem WhatsApp","whatsapp",phone),7,"admin").get("id"));
        String key=UUID.randomUUID().toString();
        var providerError=org.springframework.web.client.HttpServerErrorException.create(
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,"Server Error",
                org.springframework.http.HttpHeaders.EMPTY,
                "{\"error\":\"the number is not on WhatsApp\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8);
        when(provider.enviarTexto(anyString(),anyString(),anyString(),anyString(),eq(0)))
                .thenThrow(new RuntimeException("Uazapi API",providerError));

        var result=commercialWhatsapp.send(7,leadId,"Olá",key);
        assertEquals("FAILED",result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("O provedor não reconheceu o destinatário"));
        assertFalse(String.valueOf(result.get("message")).contains("não está cadastrado"));
        assertEquals("FAILED",db.jdbc().queryForObject("SELECT status FROM crm_commercial_sends WHERE request_key=?",String.class,key));
        assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_interactions WHERE lead_id=? AND type='MENSAGEM_COMERCIAL'",Integer.class,leadId));
        assertEquals(result,commercialWhatsapp.send(7,leadId,"Olá",key));
        verify(provider,times(1)).enviarTexto(anyString(),anyString(),anyString(),anyString(),eq(0));
    }
    @Test void providerTimeoutStillKeepsTheSendUncertain() {
        long leadId=number(crm.createForCollaborator(Map.of("nome","Loja","whatsapp",phone),7,"admin").get("id"));
        when(provider.enviarTexto(anyString(),anyString(),anyString(),anyString(),eq(0))).thenThrow(new RuntimeException("timeout"));
        var result=commercialWhatsapp.send(7,leadId,"Olá",UUID.randomUUID().toString());
        assertEquals("UNCERTAIN",result.get("status"));
    }
    @Test void collaboratorCreatesLeadInCentralCrmAndRegionAssignmentDoesNotTakeAnotherPortfolioByDefault() {
        long own=number(crm.createForCollaborator(Map.of("nome","Açaí Novo","whatsapp",phone,"cidade","Vitória","estado","ES","bairro","Praia do Canto"),7,"comercial:7").get("id"));
        assertEquals(7L,number(db.lead(own).get("assigned_collaborator_id")));
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_interactions WHERE lead_id=? AND type='COLABORADOR_ATRIBUIDO'",Integer.class,own));
        long unassigned=create("Segundo","5527997654321");
        long elsewhere=create("Terceiro","5527997654322");crm.assign(elsewhere,8L,"admin");
        long otherCity=number(crm.create(Map.of("nome","Outra cidade","whatsapp","5527997654323","cidade","Serra","estado","ES"),"admin").get("id"));
        var result=crm.assignRegion("Vitoria","es","",7,false,"admin");
        assertEquals(3,result.get("encontrados"));assertEquals(1,result.get("atribuidos"));
        assertEquals(7L,number(db.lead(unassigned).get("assigned_collaborator_id")));
        assertEquals(8L,number(db.lead(elsewhere).get("assigned_collaborator_id")));
        assertNull(db.lead(otherCity).get("assigned_collaborator_id"));
        var narrower=crm.assignRegion("Vitória","ES","Praia do Canto",9,true,"admin");
        assertEquals(1,narrower.get("atribuidos"));assertEquals(9L,number(db.lead(own).get("assigned_collaborator_id")));
        assertThrows(RuntimeException.class,()->crm.createForCollaborator(Map.of("nome","Duplicado","whatsapp",phone),7,"comercial:7"));
        assertEquals(4,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_leads",Integer.class));
    }
    @Test void repeatedAndMultiCityAssignmentsAreIdempotentAndFilteredLeadsRemainVisible() {
        long vit=number(crm.create(Map.of("nome","Açaí Vitória","whatsapp","5527997654301","cidade","Vitória","estado","ES"),"admin").get("id"));
        long ser=number(crm.create(Map.of("nome","Açaí Serra","whatsapp","5527997654302","cidade","Serra","estado","ES"),"admin").get("id"));
        long other=number(crm.create(Map.of("nome","Pizzaria Rio","whatsapp","5521997654303","cidade","Rio de Janeiro","estado","RJ"),"admin").get("id"));
        var first=crm.assign(vit,7L,"admin");assertEquals(false,first.get("alreadyAssigned"));
        var repeated=crm.assign(vit,7L,"admin");assertEquals(true,repeated.get("alreadyAssigned"));
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_interactions WHERE lead_id=? AND type='COLABORADOR_ATRIBUIDO'",Integer.class,vit));

        var region=crm.assignRegion(List.of("Vitória","Serra"),"ES","",7,false,"admin");
        assertEquals(2,region.get("encontrados"));assertEquals(1,region.get("atribuidos"));assertEquals(1,region.get("ignorados"));
        var filtered=crm.list("","",0,7L,null,List.of(),false);
        assertEquals(2L,filtered.get("total"));
        assertTrue(((List<?>)filtered.get("items")).stream().anyMatch(row->number(((Map<?,?>)row).get("id"))==vit));
        var unassigned=crm.list("","",0,null,null,List.of(),true);
        assertEquals(1L,unassigned.get("total"));
    }
    @Test void pipelinePaginatesEachStageIndependentlyAndKeepsAccurateColumnTotals() {
        long firstQualified=0;
        for(int i=0;i<51;i++) {
            long id=create("Qualificado "+i,"552799800"+String.format("%04d",i));
            if(i==0)firstQualified=id;
            crm.stage(id,"QUALIFICADO","Revisado",number(db.lead(id).get("version")),"admin");
        }
        create("Novo 1","5527998010001");create("Novo 2","5527998010002");
        crm.assign(firstQualified,7L,"admin");

        var first=crm.pipeline("","",0,null,"",List.of(),false);
        var firstItems=(List<Map<String,Object>>)first.get("items");
        assertEquals(51L,((Map<?,?>)first.get("counts")).get("QUALIFICADO"));
        assertEquals(2L,((Map<?,?>)first.get("counts")).get("NOVO_LEAD"));
        assertEquals(50,firstItems.stream().filter(r->"QUALIFICADO".equals(r.get("stage"))).count());
        assertEquals(2,firstItems.stream().filter(r->"NOVO_LEAD".equals(r.get("stage"))).count());
        var second=crm.pipeline("","",1,null,"",List.of(),false);
        var secondItems=(List<Map<String,Object>>)second.get("items");
        assertEquals(1,secondItems.stream().filter(r->"QUALIFICADO".equals(r.get("stage"))).count());
        assertEquals(0,secondItems.stream().filter(r->"NOVO_LEAD".equals(r.get("stage"))).count());

        var ownerFiltered=crm.pipeline("","QUALIFICADO",0,7L,"",List.of(),false);
        assertEquals(1L,ownerFiltered.get("total"));
        assertEquals(firstQualified,number(((List<Map<String,Object>>)ownerFiltered.get("items")).getFirst().get("id")));
    }
    @Test void collaboratorCanOnlyWorkAssignedLeadAndClicksAreAttemptsNotSuccessfulContact() {
        long id=create("A",phone);crm.assign(id,7L,"admin");
        assertThrows(RuntimeException.class,()->crm.commercialDetail(id,8));
        assertThrows(RuntimeException.class,()->crm.commercialHistory(id,8,Long.MAX_VALUE));
        assertThrows(RuntimeException.class,()->crm.contactAttempt(id,"WHATSAPP",8,"comercial:8"));
        assertThrows(RuntimeException.class,()->crm.commercialNote(id,8,"anotação","comercial:8"));
        assertThrows(RuntimeException.class,()->crm.commercialStage(id,8,"INTERESSADO","",number(db.lead(id).get("version")),"comercial:8"));
        assertFalse(crm.commercialDetail(id,7).containsKey("outbox"));
        assertFalse(crm.commercialHistory(id,7,Long.MAX_VALUE).isEmpty());
        assertThrows(RuntimeException.class,()->crm.commercialUpdate(id,8,Map.of("nome","Tentativa"),number(db.lead(id).get("version")),"comercial:8"));
        assertThrows(IllegalArgumentException.class,()->crm.commercialStage(id,7,"CLIENTE","",number(db.lead(id).get("version")),"comercial:7"));
        crm.contactAttempt(id,"WHATSAPP",7,"comercial:7");
        assertEquals("CONTATADO",db.lead(id).get("stage"));
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_interactions WHERE lead_id=? AND type='WHATSAPP_ABERTO'",Integer.class,id));
        assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_interactions WHERE lead_id=? AND type='RESPOSTA_RECEBIDA'",Integer.class,id));
    }
    @Test void assignedLeadKeepsQualificationApproachAndHistoryWhenCollaboratorEditsIt() {
        long id=create("Loja ES",phone);
        long version=number(db.lead(id).get("version"));
        crm.update(id,Map.of("nome","Loja ES","whatsapp",phone,"cidade","Vitória","estado","ES",
                "bairro","Centro","observacoes","Visita confirmada","evidencias","Foto pública da loja",
                "oportunidades","Canal próprio","informacoesPublicas","Entrega local","responsavel","Maria"),version,"admin");
        crm.prepare(id,"","admin");crm.note(id,"Primeiro atendimento feito pelo administrador","admin");crm.assign(id,7L,"admin");
        var detail=crm.commercialDetail(id,7);
        assertEquals("Foto pública da loja",((Map<?,?>)detail.get("profile")).get("evidencias"));
        assertNotNull(detail.get("prepared_message"));assertTrue(((List<?>)detail.get("timeline")).size()>=4);
        assertFalse(detail.containsKey("outbox"));
        var edited=new LinkedHashMap<String,Object>((Map<String,Object>)detail.get("profile"));edited.put("observacoes","Conversa agendada");
        crm.commercialUpdate(id,7,edited,number(detail.get("version")),"comercial:7");
        assertEquals("Foto pública da loja",db.profile(db.lead(id)).get("evidencias"));
        assertEquals("Conversa agendada",db.profile(db.lead(id)).get("observacoes"));
        assertThrows(RuntimeException.class,()->crm.commercialUpdate(id,7,edited,number(detail.get("version")),"comercial:7"));
    }
    @Test void reassignmentRevokesLeadAndAppointmentAccess() {
        long id=create("A",phone);crm.assign(id,7L,"admin");
        var appointment=crm.schedule(id,7,"PHONE",now.plusHours(2).toString(),"Retornar","comercial:7");
        long appointmentId=number(appointment.get("id"));
        crm.assign(id,8L,"admin");
        assertThrows(RuntimeException.class,()->crm.commercialDetail(id,7));
        assertThrows(RuntimeException.class,()->crm.completeAppointment(appointmentId,7,"comercial:7"));
        assertEquals("PENDING",db.jdbc().queryForMap("SELECT status FROM crm_appointments WHERE id=?",appointmentId).get("status"));
        assertEquals("COMPLETED",crm.completeAppointment(appointmentId,8,"comercial:8").get("status"));
    }
    @Test void verifiedRegistrationAfterManualOutreachLinksTrialToTheSameLead() {
        long id=create("Açaí Central",phone);crm.assign(id,7L,"admin");
        crm.contactAttempt(id,"WHATSAPP",7,"comercial:7");
        registrations.match(new CrmRegistrationListener.Registration(333L,"Açaí Central",phone));
        assertEquals(333L,number(db.lead(id).get("customer_store_id")));
        assertEquals("CLIENTE",db.lead(id).get("stage"));
    }
    @Test void collaboratorSignupCreatesAssignedCustomerLeadAndIsIdempotent() {
        var registration=new CrmRegistrationListener.Registration(334L,"Açaí da Praia",phone,7L);
        registrations.match(registration);
        var row=db.jdbc().queryForMap("SELECT * FROM crm_leads WHERE customer_store_id=334");
        assertEquals(7L,number(row.get("assigned_collaborator_id")));
        assertEquals("CLIENTE",row.get("stage"));
        assertEquals("Colaborador",db.profile(row).get("origem"));
        registrations.match(registration);
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_leads WHERE customer_store_id=334",Integer.class));
    }
    @Test void collaboratorSignupReusesTheQualifiedTrialDespiteDifferentRegistrationPhone() {
        long id=number(crm.createForCollaborator(Map.of("nome","Casole Pizzaria","telefone","5521987213854","cidade","Rio de Janeiro","estado","RJ","segmento","Pizzaria"),7,"admin").get("id"));
        crm.stage(id,"TRIAL_INICIADO","Trial aceito",number(db.lead(id).get("version")),"comercial:7");
        registrations.match(new CrmRegistrationListener.Registration(336L,"CASOLE PIZZARIA","5521999453866",7L));
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_leads",Integer.class));
        assertEquals(336L,number(db.lead(id).get("customer_store_id")));
        assertEquals("CLIENTE",db.lead(id).get("stage"));
        assertEquals("Pizzaria",db.profile(db.lead(id)).get("segmento"));
        assertEquals("5521999453866",db.profile(db.lead(id)).get("telefone"));
        assertEquals(id,db.jdbc().queryForObject("SELECT lead_id FROM crm_identities WHERE identity_key='store:336'",Long.class));
    }
    @Test void individualLinkAlwaysReusesItsAssignedLead() {
        long id=number(crm.createForCollaborator(Map.of("nome","Nome no CRM","telefone","5521987213854"),7,"admin").get("id"));
        registrations.match(new CrmRegistrationListener.Registration(337L,"Nome informado no cadastro","5521999453866",7L,id));
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_leads",Integer.class));
        assertEquals(337L,number(db.lead(id).get("customer_store_id")));
        assertEquals("Nome no CRM",db.profile(db.lead(id)).get("nome"));
        assertThrows(IllegalArgumentException.class,()->registrations.match(new CrmRegistrationListener.Registration(338L,"Outra loja","5521999453867",8L,id)));
    }
    @Test void collaboratorSignupWithSharedPhoneDoesNotStealOtherRestaurant() {
        long prior=create("Outra loja",phone);crm.assign(prior,8L,"admin");
        crm.blockNumber(phone,"Pedido para não receber mensagens");
        registrations.match(new CrmRegistrationListener.Registration(335L,"Açaí da Praia",phone,7L));
        assertEquals(8L,number(db.lead(prior).get("assigned_collaborator_id")));
        assertNull(db.lead(prior).get("customer_store_id"));
        var newLead=db.jdbc().queryForMap("SELECT * FROM crm_leads WHERE customer_store_id=335");
        assertEquals(7L,number(newLead.get("assigned_collaborator_id")));
        assertEquals(phone,db.profile(newLead).get("telefone"));
        assertTrue(bool(newLead.get("do_not_contact")));
    }
    @Test void commercialLinkRequiresValidSignature() {
        assertEquals(7L,CommercialReferralCode.verify("c7-fb1e32deb3aeeb60c017e08c","test-secret"));
        assertThrows(IllegalArgumentException.class,()->CommercialReferralCode.verify("c8-fb1e32deb3aeeb60c017e08c","test-secret"));
        assertEquals(new CommercialReferralCode.Referral(7L,123L),CommercialReferralCode.parse("c7-l3f-2192d1069ae78770ce46ebd6","test-secret"));
        assertThrows(IllegalArgumentException.class,()->CommercialReferralCode.parse("c7-l3g-2192d1069ae78770ce46ebd6","test-secret"));
    }
    void enable() { crm.config(Map.of("enabled",true,"daily_limit",40,"hourly_limit",10,"interval_seconds",60,"start_time","09:00","end_time","20:00")); }
    void advance(int seconds) { now=now.plusSeconds(seconds);doReturn(now).when((CrmStore)org.springframework.test.util.AopTestUtils.getUltimateTargetObject(db)).now(); }
    @Test void incompleteLeadsDoNotRequireUnavailableFields() {
        var lead=crm.create(Map.of("instagram","@somente_instagram"),"admin");assertNotNull(lead.get("id"));
        assertTrue(CrmInboundService.optOutText("Me tira da lista, por favor"));assertTrue(CrmInboundService.optOutText("Não me envie mais mensagens"));assertTrue(CrmInboundService.optOutText("Pare por favor"));
    }
    @Test void duplicatePhonesRollbackAndRetainAliasesAfterEdit() {
        long id=create("Pizzaria",phone);assertThrows(RuntimeException.class,()->create("Outra", "(27) 9123-4567"));
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_leads",Integer.class));
        crm.update(id,Map.of("nome","Pizzaria","whatsapp","5527998765432"),0,"admin");
        assertThrows(RuntimeException.class,()->create("Nova",phone));assertEquals(2,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_identities WHERE identity_key LIKE 'phone:%'",Integer.class));
    }
    @Test void duplicatesInstagramSiteAndPlaceAreDetected() {
        crm.create(Map.of("nome","A","instagram","@Pizzaria","site","https://www.exemplo.com/menu/?x=1","cidade","Vitória","estado","ES"),"admin");
        assertThrows(RuntimeException.class,()->crm.create(Map.of("nome","B","instagram","https://instagram.com/pizzaria/"),"admin"));
        assertThrows(RuntimeException.class,()->crm.create(Map.of("nome","C","site","http://exemplo.com/menu"),"admin"));
        assertThrows(RuntimeException.class,()->crm.create(Map.of("nome","a","cidade","vitoria","estado","es"),"admin"));
    }
    @Test void concurrentImportsCreateOnlyOneLead() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var tasks=List.<Callable<Boolean>>of(()->{try{create("A",phone);return true;}catch(RuntimeException e){return false;}},()->{try{create("A",phone);return true;}catch(RuntimeException e){return false;}});
            var result=executor.invokeAll(tasks);assertNotEquals(result.get(0).get(),result.get(1).get());assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_leads",Integer.class));
        }
    }
    @Test void optimisticEditPreventsOverwrite() {long id=create("A",phone);crm.update(id,Map.of("nome","B"),0,"admin");assertThrows(RuntimeException.class,()->crm.update(id,Map.of("nome","C"),0,"admin"));assertEquals("B",db.profile(db.lead(id)).get("nome"));}
    @Test void disabledAndDisconnectedQueueResumesOnlyOnCorrectInstance() {
        long id=ready("A",phone),q=enqueue(id);assertNull(dispatch.claim());enable();
        inst.setStatus(WhatsappInstance.Status.DESCONECTADA);assertNull(dispatch.claim());inst.setStatus(WhatsappInstance.Status.CONECTADA);
        when(instances.findByRestauranteId(21L)).thenReturn(Optional.of(instance(22)));assertNull(dispatch.claim());
        when(instances.findByRestauranteId(21L)).thenReturn(Optional.of(inst));assertEquals(q,dispatch.claim());dispatch.deliver(q);
        assertEquals("SENT",db.jdbc().queryForMap("SELECT * FROM crm_outbox WHERE id=?",q).get("status"));assertEquals("CONTATADO",db.lead(id).get("stage"));
        verify(provider).enviarTexto(eq("session-21"),eq("test-token"),eq(phone),argThat(text -> !text.contains("responda PARE")),eq(0));
    }
    @Test void idempotentRequestAndRepeatApproachAreBlocked() {
        long id=ready("A",phone),version=number(db.lead(id).get("version"));String key=UUID.randomUUID().toString();var a=dispatch.enqueue(id,"INITIAL",key,version,"admin");
        assertEquals(a.get("id"),dispatch.enqueue(id,"INITIAL",key,version,"admin").get("id"));assertThrows(RuntimeException.class,()->enqueue(id));
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_outbox",Integer.class));
    }
    @Test void cancelledBeforeAnyProviderAttemptCanBeExplicitlyQueuedAgain() {
        long id=ready("A",phone),q=enqueue(id);dispatch.cancel(id,q,"admin");long retry=enqueue(id);assertNotEquals(q,retry);enable();assertEquals(retry,dispatch.claim());dispatch.deliver(retry);
        verify(provider,times(1)).enviarTexto(anyString(),anyString(),anyString(),anyString(),eq(0));
    }
    @Test void timeoutAndCrashAreNotRetried() {
        long id=ready("A",phone),q=enqueue(id);enable();assertEquals(q,dispatch.claim());
        when(provider.enviarTexto(anyString(),anyString(),anyString(),anyString(),eq(0))).thenThrow(new RuntimeException("timeout"));dispatch.deliver(q);
        advance(180);assertNull(dispatch.claim());assertEquals("UNCERTAIN",db.jdbc().queryForMap("SELECT * FROM crm_outbox WHERE id=?",q).get("status"));
        assertThrows(RuntimeException.class,()->enqueue(id));verify(provider,times(1)).enviarTexto(anyString(),anyString(),anyString(),anyString(),eq(0));
        long b=ready("B","5527997654321"),qb=enqueue(b);assertEquals(qb,dispatch.claim());advance(180);assertNull(dispatch.claim());assertEquals("UNCERTAIN",db.jdbc().queryForMap("SELECT * FROM crm_outbox WHERE id=?",qb).get("status"));
    }
    @Test void heartbeatFailureDoesNotUndoAcknowledgedSend() {long id=ready("A",phone),q=enqueue(id);enable();dispatch.claim();doThrow(new RuntimeException()).when(whatsapp).marcarRespostaEnviada(inst);dispatch.deliver(q);dispatch.refreshHeartbeat(q);assertEquals("SENT",db.jdbc().queryForMap("SELECT * FROM crm_outbox WHERE id=?",q).get("status"));}
    @Test void optOutOverridesHandoffAndSurvivesServiceRecreation() {
        long id=ready("A",phone),q=enqueue(id);crm.handoff(id,"EM_ATENDIMENTO_HUMANO","admin");
        inbound.receive(inst,phone+"@s.whatsapp.net","stop-1","Não quero mais receber mensagens",false,false,100);
        assertTrue(bool(db.lead(id).get("do_not_contact")));assertEquals("CANCELLED",db.jdbc().queryForMap("SELECT * FROM crm_outbox WHERE id=?",q).get("status"));
        var recreated=new CrmInboundService(db,crm);assertTrue(recreated.receive(inst,phone+"@s.whatsapp.net","stop-1","Não quero mais receber mensagens",false,false,100));
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_interactions WHERE type='RESPOSTA_RECEBIDA'",Integer.class));
        assertThrows(RuntimeException.class,()->dispatch.enqueue(id,"MANUAL",UUID.randomUUID().toString(),number(db.lead(id).get("version")),"admin"));
        var p=db.profile(db.lead(id));p.put("whatsapp","5527998765000");crm.update(id,p,number(db.lead(id).get("version")),"admin");assertTrue(bool(db.lead(id).get("do_not_contact")));assertTrue(crm.blockedPhone("2791234567"));
    }
    @Test void otherStoreIsUntouchedEvenWithSameContact() {
        long id=create("A",phone);assertFalse(inbound.receive(instance(22),phone+"@s.whatsapp.net","same","PARE",false,false,100));
        assertFalse(bool(db.lead(id).get("do_not_contact")));assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_interactions",Integer.class));verifyNoInteractions(provider);
    }
    @Test void responseInterestAndManualHandoffPersistWithoutAutoRelease() {
        long id=ready("A",phone),q=enqueue(id);enable();dispatch.claim();dispatch.deliver(q);
        advance(2);
        inbound.receive(inst,phone+"@s.whatsapp.net","reply-1","Quero conhecer",false,false,100);
        assertEquals("RESPONDEU",db.lead(id).get("stage"));assertEquals("AGUARDANDO_ATENDIMENTO_HUMANO",db.lead(id).get("handoff"));
        inbound.receive(inst,phone+"@s.whatsapp.net","manual-1","Olá",true,false,101);advance(86400);
        inbound.receive(inst,phone+"@s.whatsapp.net","reply-2","cardápio",false,false,102);
        assertEquals("EM_ATENDIMENTO_HUMANO",db.lead(id).get("handoff"));verify(provider,times(1)).enviarTexto(anyString(),anyString(),anyString(),anyString(),eq(0));
        assertFalse(CrmInboundService.interestText("Não quero conhecer"));
    }
    @Test void windowIntervalAndDailyLimitAreEnforced() {
        long id=ready("A",phone),q=enqueue(id);crm.config(Map.of("enabled",true,"daily_limit",1,"hourly_limit",1,"interval_seconds",60,"start_time","11:00","end_time","20:00"));assertNull(dispatch.claim());advance(3600);assertEquals(q,dispatch.claim());dispatch.deliver(q);
        enqueue(ready("B","5527997654321"));advance(10);assertNull(dispatch.claim());advance(3600);assertNull(dispatch.claim());advance(86400);assertNotNull(dispatch.claim());
    }
    @Test void handoffBetweenClaimAndSendCancelsDispatch() {long id=ready("A",phone),q=enqueue(id);enable();dispatch.claim();crm.handoff(id,"EM_ATENDIMENTO_HUMANO","admin");dispatch.deliver(q);verifyNoInteractions(whatsapp);verify(provider,never()).enviarTexto(anyString(),anyString(),anyString(),anyString(),anyInt());}
    @Test void metricsUseContactCohortAndDoNotCountEarlierReplies() {
        long id=ready("A",phone);inbound.receive(inst,phone+"@s.whatsapp.net","before","Oi",false,false,1);advance(60);long q=enqueue(id);enable();dispatch.claim();dispatch.deliver(q);
        var m=crm.metrics(now.toLocalDate(),now.toLocalDate());assertEquals(1L,m.get("contatados"));assertEquals(0L,m.get("respostas"));
        advance(60);inbound.receive(inst,phone+"@s.whatsapp.net","after","Quero conhecer",false,false,2);
        m=crm.metrics(now.toLocalDate(),now.toLocalDate());assertEquals(1L,m.get("respostas"));assertEquals(100.0,m.get("taxaResposta"));assertEquals(0L,m.get("interessados"));
    }
    @Test void immediateRepliesStayInHistoryWithoutInflatingCohortOrInterest() {
        long id=ready("A",phone),q=enqueue(id);enable();dispatch.claim();dispatch.deliver(q);
        long sent=now.atZone(ZONE).toInstant().toEpochMilli();advance(30);
        // A delayed webhook must use message time, not arrival time.
        inbound.receive(inst,phone+"@s.whatsapp.net","bot","Quero conhecer",false,false,sent+1999);
        inbound.receive(inst,phone+"@s.whatsapp.net","bot","Quero conhecer",false,false,sent+1999);
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_interactions WHERE type='RESPOSTA_DESCONSIDERADA'",Integer.class));
        assertEquals(0L,crm.metrics(now.toLocalDate(),now.toLocalDate()).get("respostas"));
        assertEquals("CONTATADO",db.lead(id).get("stage"));assertEquals("AUTOMATICO",db.lead(id).get("handoff"));
        inbound.receive(inst,phone+"@s.whatsapp.net","human","Quero conhecer",false,false,sent+2000);
        assertEquals(1L,crm.metrics(now.toLocalDate(),now.toLocalDate()).get("respostas"));
        assertEquals("RESPONDEU",db.lead(id).get("stage"));assertEquals("AGUARDANDO_ATENDIMENTO_HUMANO",db.lead(id).get("handoff"));
    }
    @Test void immediateOptOutStillBlocksContact() {
        long id=ready("A",phone),q=enqueue(id);enable();dispatch.claim();dispatch.deliver(q);advance(1);
        inbound.receive(inst,phone+"@s.whatsapp.net","fast-stop","Não me envie mais mensagens",false,false,null);
        assertTrue(bool(db.lead(id).get("do_not_contact")));
        assertEquals(0L,crm.metrics(now.toLocalDate(),now.toLocalDate()).get("respostas"));
    }
    @Test void secondsPrecisionAndManualMessagesUseLatestOutbound() {
        long id=ready("A",phone),q=enqueue(id);enable();dispatch.claim();dispatch.deliver(q);advance(30);
        db.event(id,"MENSAGEM_HUMANA",Map.of("texto","Bom dia"),"whatsapp");
        long sent=now.atZone(ZONE).toEpochSecond();advance(10);
        inbound.receive(inst,phone+"@s.whatsapp.net","fast-manual","Bem-vindo",false,false,sent+1);
        assertEquals(0L,crm.metrics(now.toLocalDate(),now.toLocalDate()).get("respostas"));
        inbound.receive(inst,phone+"@s.whatsapp.net","later","Quem está falando?",false,false,sent+5);
        assertEquals(1L,crm.metrics(now.toLocalDate(),now.toLocalDate()).get("respostas"));
    }
    @Test void signupRequiresActualContactAndExactNameBeforeAutomaticConversion() {
        long id=ready("A",phone);registrations.match(new CrmRegistrationListener.Registration(100,"Outra",phone));assertNotEquals("CLIENTE",db.lead(id).get("stage"));
        crm.handoff(id,"AUTOMATICO","admin");long q=enqueue(id);enable();dispatch.claim();dispatch.deliver(q);
        registrations.match(new CrmRegistrationListener.Registration(100,"A",phone));assertEquals("CLIENTE",db.lead(id).get("stage"));assertEquals(100L,number(db.lead(id).get("customer_store_id")));
    }
}
