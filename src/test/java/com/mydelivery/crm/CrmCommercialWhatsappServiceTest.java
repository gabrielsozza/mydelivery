package com.mydelivery.crm;

import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.UazapiClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrmCommercialWhatsappServiceTest {
    private final CrmStore store=mock(CrmStore.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WhatsappInstanceRepository instances=mock(WhatsappInstanceRepository.class);
    private final UazapiClient provider=mock(UazapiClient.class);
    private final CrmCommercialWhatsappService service=new CrmCommercialWhatsappService(store,instances,provider,new CrmOutboundPolicy(false));

    @Test void conversationReadAndSendRejectOtherAnalyst() {
        when(store.lead(9)).thenReturn(Map.of("id",9L,"assigned_collaborator_id",32L));
        assertThrows(ResponseStatusException.class,()->service.conversation(31,9,0));
        assertThrows(ResponseStatusException.class,()->service.read(31,9));
        assertThrows(ResponseStatusException.class,()->service.send(31,9,"Olá","1234567890123456"));
        verifyNoInteractions(instances,provider);
    }

    @Test void manualOnlyModeStillProtectsOtherAnalystsWhileAllowingHumanSendFlow() {
        var paused=new CrmCommercialWhatsappService(store,instances,provider,new CrmOutboundPolicy(true));
        when(store.lead(9)).thenReturn(Map.of("id",9L,"assigned_collaborator_id",32L));
        assertThrows(ResponseStatusException.class,()->paused.send(31,9,"Olá","1234567890123456"));
        verify(store).lead(9);
        verifyNoInteractions(instances,provider);
    }

    @Test void stageFilterUsesCurrentCrmStageForOnlyAuthenticatedAnalyst() {
        when(store.jdbc()).thenReturn(jdbc);
        Map<String,Object> trial=new HashMap<>(Map.of("id",1L,"stage","TRIAL_INICIADO","version",2L,"unread",1L,"last_id",12L,"last_type","RESPOSTA_RECEBIDA","last_detail","{}","last_at",Timestamp.valueOf(LocalDateTime.now())));
        Map<String,Object> interest=new HashMap<>(Map.of("id",2L,"stage","INTERESSADO","version",1L,"unread",0L,"last_id",10L,"last_type","MENSAGEM_COMERCIAL","last_detail","{}","last_at",Timestamp.valueOf(LocalDateTime.now())));
        trial.put("profile","{}");interest.put("profile","{}");
        when(jdbc.queryForList(anyString(),eq(31L))).thenReturn(List.of(trial,interest));
        when(store.profile(anyMap())).thenReturn(Map.of("nome","Loja","telefone","5511999999999"));
        var result=service.inbox(31,"TRIAL_INICIADO","");
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)result.get("conversations");
        assertEquals(1,rows.size());assertEquals(1L,rows.getFirst().get("leadId"));
        assertEquals(2,result.get("total"));assertEquals(1,result.get("unread"));
        verify(jdbc).queryForList(contains("l.assigned_collaborator_id=?"),eq(31L));
    }
}
