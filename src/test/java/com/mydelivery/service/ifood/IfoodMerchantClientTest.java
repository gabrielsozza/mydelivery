package com.mydelivery.service.ifood;

import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class IfoodMerchantClientTest {
    MockRestServiceServer server;
    IfoodMerchantClient client;
    String url = "https://merchant-api.ifood.com.br/merchant/v1.0/merchants/store/interruptions";

    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://merchant-api.ifood.com.br");
        server = MockRestServiceServer.bindTo(builder).build();
        var auth = mock(IfoodClient.class);
        when(auth.obterAccessToken()).thenReturn("test-token");
        client = new IfoodMerchantClient(auth, builder.build());
    }

    void timeout() {
        server.expect(requestTo(url + "/pause"))
                .andExpect(method(HttpMethod.DELETE)).andExpect(header("Content-Length", "0"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
    }
    void list(String json) {
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test void newlyAuthorizedStoreRetriesWithFreshToken() {
        var builder=RestClient.builder().baseUrl("https://merchant-api.ifood.com.br");
        var http=MockRestServiceServer.bindTo(builder).build();
        var auth=mock(IfoodClient.class);
        when(auth.obterAccessToken()).thenReturn("old-token","new-token");
        var c=new IfoodMerchantClient(auth,builder.build());
        http.expect(requestTo("https://merchant-api.ifood.com.br/merchant/v1.0/merchants/biss"))
                .andExpect(header("Authorization","Bearer old-token")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        http.expect(requestTo("https://merchant-api.ifood.com.br/merchant/v1.0/merchants/biss"))
                .andExpect(header("Authorization","Bearer new-token"))
                .andRespond(withSuccess("{\"id\":\"biss\",\"name\":\"Biss Açaí\"}",MediaType.APPLICATION_JSON));
        assertEquals("biss",c.detalhesMerchant("biss").get("id"));
        verify(auth).invalidarAccessToken("old-token");http.verify();
    }
    @Test void forbiddenStoreIsStillRejectedAfterOneTokenRefresh() {
        for(int i=0;i<2;i++)server.expect(requestTo("https://merchant-api.ifood.com.br/merchant/v1.0/merchants/biss"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertEquals(403,assertThrows(IfoodApiException.class,()->client.detalhesMerchant("biss")).getStatusIfood());
        server.verify();
    }

    @Test void removesWithEmptyBodyAnd204() {
        server.expect(requestTo(url + "/pause")).andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Content-Length", "0")).andRespond(withNoContent());
        client.removerInterrupcao("store", "pause");
        server.verify();
    }
    @Test void timeoutAfterAppliedDeletionIsReconciledWithoutRepeating() {
        timeout(); list("[]");
        assertDoesNotThrow(() -> client.removerInterrupcao("store", "pause"));
        server.verify();
    }
    @Test void retriesOnceIfPauseStillExists() {
        timeout(); list("[{\"id\":\"pause\"}]");
        server.expect(requestTo(url + "/pause")).andRespond(withNoContent());
        client.removerInterrupcao("store", "pause");
        server.verify();
    }
    @Test void neverClaimsSuccessWhenPauseRemains() {
        timeout(); list("[{\"id\":\"pause\"}]");
        timeout(); list("[{\"id\":\"pause\"}]");
        var error = assertThrows(IfoodApiException.class, () -> client.removerInterrupcao("store", "pause"));
        assertEquals(504, error.getStatusIfood());
        server.verify();
    }
    @Test void unavailableListDoesNotCountAsConfirmation() {
        timeout(); list("null"); timeout(); list("null");
        assertThrows(IfoodApiException.class, () -> client.removerInterrupcao("store", "pause"));
        server.verify();
    }
    @Test void missingPauseIsIdempotentOnlyAfterConfirmingList() {
        server.expect(requestTo(url + "/pause")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        list("[]");
        client.removerInterrupcao("store", "pause");
        server.verify();
    }
    @Test void authorizationFailureIsNotRetriedOrHidden() {
        server.expect(requestTo(url + "/pause")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThrows(IfoodApiException.class, () -> client.removerInterrupcao("store", "pause"));
        server.verify();
    }
}
