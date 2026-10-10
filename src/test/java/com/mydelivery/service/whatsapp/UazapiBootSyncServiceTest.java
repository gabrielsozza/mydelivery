package com.mydelivery.service.whatsapp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappInstanceRepository;

class UazapiBootSyncServiceTest {
    @Test
    void tokenAtualizadoNoBancoTambemAtualizaCacheDoCliente() {
        UazapiClient cliente = mock(UazapiClient.class);
        WhatsappInstanceRepository repo = mock(WhatsappInstanceRepository.class);
        WhatsappInstance instancia = new WhatsappInstance();
        instancia.setId(9L);
        instancia.setInstanceName("loja-teste");
        instancia.setInstanceToken("antigo");
        instancia.setStatus(WhatsappInstance.Status.CONECTADA);
        when(cliente.fetchInstancesRaw()).thenReturn(List.of(Map.of(
                "name", "loja-teste", "token", "novo", "status", "connected")));
        when(repo.findByInstanceName("loja-teste")).thenReturn(Optional.of(instancia));
        when(repo.findAll()).thenReturn(List.of(instancia));

        new UazapiBootSyncService(cliente, repo).sincronizarComLog("teste");

        assertEquals("novo", instancia.getInstanceToken());
        verify(cliente).registrarToken("loja-teste", "novo");
        verify(repo).save(instancia);
    }
}
