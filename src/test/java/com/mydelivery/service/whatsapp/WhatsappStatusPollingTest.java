package com.mydelivery.service.whatsapp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mydelivery.config.EvolutionProperties;
import com.mydelivery.model.Restaurante;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappDesconexaoLogRepository;
import com.mydelivery.repository.WhatsappHealthLogRepository;
import com.mydelivery.repository.WhatsappInstanceRepository;

class WhatsappStatusPollingTest {
    private WhatsappService service;
    private WhatsappInstanceRepository repo;
    private UazapiClient uazapi;
    private Restaurante loja;
    private WhatsappInstance instancia;

    @BeforeEach
    void preparar() {
        repo = mock(WhatsappInstanceRepository.class);
        uazapi = mock(UazapiClient.class);
        service = new WhatsappService(repo, mock(WhatsappHealthLogRepository.class),
                mock(WhatsappDesconexaoLogRepository.class), mock(org.springframework.jdbc.core.JdbcTemplate.class),
                uazapi, mock(EvolutionProperties.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        loja = new Restaurante();
        loja.setId(9L);
        instancia = new WhatsappInstance();
        instancia.setInstanceName("loja-teste");
        when(repo.findByRestauranteId(9L)).thenReturn(Optional.of(instancia));
        when(repo.save(instancia)).thenReturn(instancia);
    }

    @Test
    void fechamentoTransitorioNoPollingNaoDesconecta() {
        instancia.setStatus(WhatsappInstance.Status.CONECTADA);
        when(uazapi.consultarStatus("loja-teste"))
                .thenReturn(Map.of("instance", Map.of("state", "close")));
        when(uazapi.fetchInstancesRaw())
                .thenReturn(List.of(Map.of("name", "loja-teste", "status", "connected")));

        service.status(loja);

        assertEquals(WhatsappInstance.Status.CONECTADA, instancia.getStatus());
    }

    @Test
    void sessaoAbertaCorrigeStatusLocalSemAcaoManual() {
        instancia.setStatus(WhatsappInstance.Status.DESCONECTADA);
        instancia.setDesconectadoManualmente(false);
        when(uazapi.consultarStatus("loja-teste"))
                .thenReturn(Map.of("instance", Map.of("state", "open")));

        service.status(loja);

        assertEquals(WhatsappInstance.Status.CONECTADA, instancia.getStatus());
    }

    @Test
    void respeitaDesconexaoManualRecente() {
        instancia.setStatus(WhatsappInstance.Status.DESCONECTADA);
        instancia.setDesconectadoManualmente(true);
        when(uazapi.consultarStatus("loja-teste"))
                .thenReturn(Map.of("instance", Map.of("state", "open")));

        service.status(loja);

        assertEquals(WhatsappInstance.Status.DESCONECTADA, instancia.getStatus());
    }
}
