package com.mydelivery.service.ifood;

import com.mydelivery.controller.IfoodController;
import com.mydelivery.controller.IfoodMerchantController;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IfoodConnectionServiceTest {
    final String merchantId="03ae19c5-f91d-4c19-b6ff-4f30a88d5e1a";
    final IfoodMerchantClient merchant=mock(IfoodMerchantClient.class);
    final IfoodConnectionService service=new IfoodConnectionService(merchant);
    Restaurante restaurant() {
        var r=new Restaurante();r.setId(58L);r.setIfoodMerchantId(merchantId);
        r.setIfoodIntegracaoAtiva(true);r.setIfoodUltimoPollingEm(LocalDateTime.now());return r;
    }
    @Test void savedIdAndRecentGlobalPollingDoNotProveStoreAuthorization() {
        when(merchant.detalhesMerchant(merchantId)).thenThrow(new IfoodApiException(403,"Forbidden","details"));
        var result=service.consultar(restaurant());
        assertEquals(true,result.get("configurado"));
        assertEquals(false,result.get("conectado"));assertEquals(false,result.get("ativo"));
        assertEquals("SEM_AUTORIZACAO",result.get("statusIntegracao"));
        assertTrue(result.get("mensagem").toString().contains("Permissões"));
    }
    @Test void authorizedStoreUsesItsOwnName() {
        when(merchant.detalhesMerchant(merchantId)).thenReturn(Map.of("id",merchantId,"name","Biss Açaí"));
        var result=service.consultar(restaurant());
        assertEquals(true,result.get("conectado"));assertEquals(true,result.get("ativo"));
        assertEquals("Biss Açaí",result.get("nomeLoja"));
    }
    @Test void providerOutageIsNotMisreportedAsMissingPermission() {
        when(merchant.detalhesMerchant(merchantId)).thenThrow(new IfoodApiException(500,"Unavailable","details"));
        assertEquals("INDISPONIVEL",service.consultar(restaurant()).get("statusIntegracao"));
    }
    @Test void unexpectedStoreIsNeverAccepted() {
        when(merchant.detalhesMerchant(merchantId)).thenReturn(Map.of("id","test-store","name","Teste"));
        assertEquals(false,service.consultar(restaurant()).get("conectado"));
    }
    @Test void restaurantListingNeverCallsGlobalStoreList() {
        var repo=mock(RestauranteRepository.class);
        when(repo.findByUsuarioEmail("owner")).thenReturn(Optional.of(restaurant()));
        when(merchant.detalhesMerchant(merchantId)).thenReturn(Map.of("id",merchantId,"name","Biss Açaí"));
        var controller=new IfoodMerchantController(repo,merchant);
        var list=controller.listar("owner").getBody();
        assertEquals(1,list.size());assertEquals(merchantId,list.getFirst().get("id"));
        verify(merchant,never()).listarMerchants();
    }
    @Test void forbiddenStoreDoesNotFallBackToTestStore() {
        var repo=mock(RestauranteRepository.class);
        when(repo.findByUsuarioEmail("owner")).thenReturn(Optional.of(restaurant()));
        when(merchant.detalhesMerchant(merchantId)).thenThrow(new IfoodApiException(403,"Forbidden","details"));
        assertThrows(IfoodApiException.class,()->new IfoodMerchantController(repo,merchant).listar("owner"));
        verify(merchant,never()).listarMerchants();
    }
    @Test void rejectedConnectionDoesNotOverwriteExistingBinding() {
        var repo=mock(RestauranteRepository.class);var r=restaurant();r.setIfoodMerchantId("old-store");
        when(repo.findByUsuarioEmail("owner")).thenReturn(Optional.of(r));
        when(merchant.detalhesMerchant(merchantId)).thenThrow(new IfoodApiException(403,"Forbidden","details"));
        var controller=new IfoodController(repo,mock(IfoodClient.class),service);
        assertThrows(ResponseStatusException.class,()->controller.conectar("owner",Map.of("merchantId",merchantId)));
        assertEquals("old-store",r.getIfoodMerchantId());verify(repo,never()).save(any());
    }
    @Test void storeAlreadyLinkedElsewhereIsRejected() {
        var repo=mock(RestauranteRepository.class);var existing=restaurant();existing.setId(99L);
        when(repo.findByUsuarioEmail("owner")).thenReturn(Optional.of(restaurant()));
        when(repo.findByIfoodMerchantId(merchantId)).thenReturn(Optional.of(existing));
        var controller=new IfoodController(repo,mock(IfoodClient.class),service);
        var error=assertThrows(ResponseStatusException.class,()->controller.conectar("owner",Map.of("merchantId",merchantId)));
        assertEquals(409,error.getStatusCode().value());verifyNoInteractions(merchant);verify(repo,never()).save(any());
    }
    @Test void unauthorizedStoreDoesNotExposeSuccessfulPollingTimestamp() {
        var repo=mock(RestauranteRepository.class);
        when(repo.findByUsuarioEmail("owner")).thenReturn(Optional.of(restaurant()));
        when(merchant.detalhesMerchant(merchantId)).thenThrow(new IfoodApiException(403,"Forbidden","details"));
        var result=new IfoodController(repo,mock(IfoodClient.class),service).status("owner").getBody();
        assertFalse(result.containsKey("ultimoPollingEm"));assertEquals(false,result.get("conectado"));
    }
}
