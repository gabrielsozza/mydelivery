package com.mydelivery.crm;

import com.mydelivery.controller.WhatsappWebhookController;
import com.mydelivery.model.*;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.whatsapp.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CrmRoutingAndSecurityTest {
    @Test void rejectedCommercialSendReturnsAnHttpErrorWithTheReasonForExistingClients() throws Exception {
        var commercial=mock(CrmCommercialWhatsappService.class);
        var controller=new CrmInternalController(mock(CrmService.class),mock(CrmDispatchService.class),commercial,
                mock(RestauranteRepository.class),mock(WhatsappService.class));
        ReflectionTestUtils.setField(controller,"secret","test-internal");
        var result=Map.<String,Object>of("status","FAILED","requestKey","test-request-key","message","Este número não está cadastrado no WhatsApp.");
        when(commercial.send(7,9,"Olá","test-request-key","comercial:7")).thenReturn(result);
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/admin-internal/crm/commercial-whatsapp/9/send")
                .header("X-Admin-Secret","test-internal").header("X-CRM-Actor","comercial:7")
                .contentType("application/json").content("{\"collaboratorId\":7,\"message\":\"Olá\",\"requestKey\":\"test-request-key\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value(result.get("message")));
    }
    @Test void internalSecretFailsClosedAndCannotSelectAnotherStore() throws Exception {
        var crm=mock(CrmService.class);var dispatch=mock(CrmDispatchService.class);var restaurants=mock(RestauranteRepository.class);var wa=mock(WhatsappService.class);
        var c=new CrmInternalController(crm,dispatch,restaurants,wa);ReflectionTestUtils.setField(c,"secret","test-internal");MockMvc mvc=MockMvcBuilders.standaloneSetup(c).build();
        mvc.perform(get("/api/admin-internal/crm/config")).andExpect(status().isForbidden());verifyNoInteractions(crm);
        mvc.perform(get("/api/admin-internal/crm/config").header("X-Admin-Secret","wrong")).andExpect(status().isForbidden());
        when(crm.config()).thenReturn(Map.of("storeId",21));mvc.perform(get("/api/admin-internal/crm/config").header("X-Admin-Secret","test-internal")).andExpect(status().isOk());
        var rest=Restaurante.builder().id(21L).build();when(restaurants.findById(21L)).thenReturn(Optional.of(rest));
        var inst=CrmIntegrationTest.instance(21);inst.setQrCode("base64");when(wa.conectar(rest,true)).thenReturn(inst);
        mvc.perform(post("/api/admin-internal/crm/whatsapp/connect").header("X-Admin-Secret","test-internal").contentType("application/json").content("{\"storeId\":22}")).andExpect(status().isOk()).andExpect(jsonPath("$.storeId").value(21)).andExpect(jsonPath("$.instanceToken").doesNotExist());verify(restaurants,never()).findById(22L);verify(wa).conectar(rest,true);verifyNoInteractions(dispatch);
        ReflectionTestUtils.setField(c,"secret","");mvc.perform(get("/api/admin-internal/crm/config").header("X-Admin-Secret","test-internal")).andExpect(status().isServiceUnavailable());
    }
    @Test void store21RunsCrmBeforeRestaurantTakeoverOrBacklogFilters() {
        var wa=mock(WhatsappService.class);var bot=mock(WhatsappBotService.class);var crm=mock(CrmInboundService.class);var controller=new WhatsappWebhookController(wa,bot,crm);
        var inst=CrmIntegrationTest.instance(21);when(wa.buscarPorNome("session-21")).thenReturn(inst);
        when(crm.receive(eq(inst),anyString(),anyString(),anyString(),anyBoolean(),anyBoolean(),any())).thenReturn(true);
        var payload=Map.<String,Object>of("event","messages.upsert","data",Map.of("key",Map.of("remoteJid","5527991234567@s.whatsapp.net","id","a","fromMe",true),"message",Map.of("conversation","PARE"),"messageTimestamp",1));
        assertEquals(200,controller.processarWebhookEvolution("session-21",payload).getStatusCode().value());verify(crm).receive(inst,"5527991234567@s.whatsapp.net","a","PARE",true,false,1);verifyNoInteractions(bot);
        doThrow(new RuntimeException("DB unavailable")).when(crm).receive(eq(inst),anyString(),anyString(),anyString(),anyBoolean(),anyBoolean(),any());assertEquals(503,controller.processarWebhookEvolution("session-21",payload).getStatusCode().value());
    }
    @Test void ordinaryStoreStillReachesExistingBot() {
        var wa=mock(WhatsappService.class);var bot=mock(WhatsappBotService.class);var crm=mock(CrmInboundService.class);var controller=new WhatsappWebhookController(wa,bot,crm);
        var inst=CrmIntegrationTest.instance(22);when(wa.buscarPorNome("session-22")).thenReturn(inst);
        var payload=Map.<String,Object>of("event","messages.upsert","data",Map.of("key",Map.of("remoteJid","5527991234567@s.whatsapp.net","id","normal-22","fromMe",false),"message",Map.of("conversation","cardápio"),"messageTimestamp",System.currentTimeMillis()/1000));
        controller.processarWebhookEvolution("session-22",payload);verify(bot,timeout(6000)).processar(inst,"5527991234567@s.whatsapp.net","cardápio");
    }
    @Test void dataDrivenApproachRequiresItsFacts() throws Exception {
        var service=new CrmApproachService(new com.fasterxml.jackson.databind.ObjectMapper());
        var generated=service.prepare(Map.of("nome","Loja A","utilizaIfood",true),"");assertEquals("pedidos_proprios",generated.get("abordagem"));assertTrue(generated.get("mensagem").toString().contains("Loja A"));
        assertThrows(IllegalArgumentException.class,()->service.prepare(Map.of("nome","Loja B"),"pedidos_proprios"));
    }
}
