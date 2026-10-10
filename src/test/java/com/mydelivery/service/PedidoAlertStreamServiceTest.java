package com.mydelivery.service;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PedidoAlertStreamServiceTest {
    @Test void enviaSinaisParaTodasAsConexoesSemConsultarPedidos() throws Exception {
        var service = new PedidoAlertStreamService();
        var a = mock(SseEmitter.class); var b = mock(SseEmitter.class);
        service.registrar(a); service.registrar(b);
        service.sinalizar(); service.sinalizar();
        verify(a, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(b, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }
    @Test void falhaDeUmClienteNaoInterrompeOutrosENaoRepeteConexaoMorta() throws Exception {
        var service = new PedidoAlertStreamService();
        var dead = mock(SseEmitter.class); var active = mock(SseEmitter.class);
        doThrow(new IOException("desconectado")).when(dead).send(any(SseEmitter.SseEventBuilder.class));
        service.registrar(dead); service.registrar(active);
        service.sinalizar(); service.sinalizar();
        verify(dead).send(any(SseEmitter.SseEventBuilder.class));
        verify(dead).completeWithError(any(IOException.class));
        verify(active, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }
    @Test void conclusaoRemoveConexao() throws Exception {
        var service = new PedidoAlertStreamService();
        var emitter = mock(SseEmitter.class);
        var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        service.registrar(emitter);
        verify(emitter).onCompletion(callback.capture());
        callback.getValue().run(); service.sinalizar();
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }
}
