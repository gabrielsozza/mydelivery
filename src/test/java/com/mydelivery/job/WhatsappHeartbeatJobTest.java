package com.mydelivery.job;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.UazapiClient;
import com.mydelivery.service.whatsapp.WhatsappService;

class WhatsappHeartbeatJobTest {
    private WhatsappInstanceRepository repo;
    private UazapiClient uazapi;
    private WhatsappService whatsapp;
    private WhatsappHeartbeatJob job;
    private WhatsappInstance instancia;

    @BeforeEach
    void preparar() {
        repo = mock(WhatsappInstanceRepository.class);
        uazapi = mock(UazapiClient.class);
        whatsapp = mock(WhatsappService.class);
        job = new WhatsappHeartbeatJob(repo, uazapi, whatsapp);
        instancia = new WhatsappInstance();
        instancia.setInstanceName("loja-teste");
        instancia.setStatus(WhatsappInstance.Status.CONECTADA);
        instancia.setHeartbeatsFalhadosSeguidos(0);
    }

    @Test
    void connectingTransitorioNaoDesconectaSessaoAtiva() {
        when(uazapi.consultarStatus("loja-teste"))
                .thenReturn(Map.of("instance", Map.of("state", "connecting")));
        when(uazapi.fetchInstancesRaw())
                .thenReturn(List.of(Map.of("name", "loja-teste", "status", "connected")));

        job.avaliarUma(instancia);

        assertEquals(WhatsappInstance.Status.CONECTADA, instancia.getStatus());
        assertEquals(0, instancia.getHeartbeatsFalhadosSeguidos());
        verify(whatsapp, never()).registrarEventoAuditoria(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void quedaConfirmadaPorAmbasConsultasMarcaDesconectada() {
        when(uazapi.consultarStatus("loja-teste"))
                .thenReturn(Map.of("instance", Map.of("state", "close")));
        when(uazapi.fetchInstancesRaw())
                .thenReturn(List.of(Map.of("name", "loja-teste", "status", "disconnected")));

        job.avaliarUma(instancia);

        assertEquals(WhatsappInstance.Status.DESCONECTADA, instancia.getStatus());
        verify(whatsapp).registrarEventoAuditoria(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void falhaDeAutenticacaoNaoDesconectaSessaoSemProvaDaQueda() {
        instancia.setHeartbeatsFalhadosSeguidos(2);
        when(uazapi.consultarStatus("loja-teste"))
                .thenThrow(new RuntimeException("401 Invalid token"));

        job.avaliarUma(instancia);

        assertEquals(WhatsappInstance.Status.CONECTADA, instancia.getStatus());
        assertEquals(3, instancia.getHeartbeatsFalhadosSeguidos());
        verify(whatsapp, never()).registrarEventoAuditoria(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
