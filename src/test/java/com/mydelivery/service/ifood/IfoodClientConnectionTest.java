package com.mydelivery.service.ifood;

import com.mydelivery.config.IfoodProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class IfoodClientConnectionTest {
    @Test void failedPollingDoesNotReturnSuccessfulEmptyList() {
        var builder=RestClient.builder().baseUrl("https://ifood.test");
        var server=MockRestServiceServer.bindTo(builder).build();
        var client=new IfoodClient(new IfoodProperties());
        ReflectionTestUtils.setField(client,"restClient",builder.build());
        server.expect(requestTo("https://ifood.test/authentication/v1.0/oauth/token"))
                .andRespond(withSuccess("{\"accessToken\":\"test-token\",\"expiresIn\":3600}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://ifood.test/order/v1.0/events:polling")).andRespond(withServerError());
        assertThrows(IfoodApiException.class,client::pollingEventos);server.verify();
    }
    @Test void appWithoutAnyGrantedStoreKeepsAuthorizationError() {
        var builder=RestClient.builder().baseUrl("https://ifood.test");
        var server=MockRestServiceServer.bindTo(builder).build();
        var client=new IfoodClient(new IfoodProperties());
        ReflectionTestUtils.setField(client,"restClient",builder.build());
        server.expect(requestTo("https://ifood.test/authentication/v1.0/oauth/token"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"error\":{\"message\":\"No permissions granted to client\"}}"));
        assertEquals(403,assertThrows(IfoodApiException.class,client::obterAccessToken).getStatusIfood());server.verify();
    }
}
