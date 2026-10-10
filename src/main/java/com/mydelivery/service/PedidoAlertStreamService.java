package com.mydelivery.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Mantém a consulta de pedidos ativa sem depender dos timers da aba oculta.
 * O sinal não contém dados: a consulta autenticada existente continua responsável
 * por selecionar os pedidos do restaurante do usuário.
 */
@Service
public class PedidoAlertStreamService {
    private final Set<SseEmitter> connections = ConcurrentHashMap.newKeySet();

    public SseEmitter abrir() {
        // Renova periodicamente a conexão e a autenticação do usuário.
        SseEmitter emitter = new SseEmitter(240_000L);
        registrar(emitter);
        enviar(emitter);
        return emitter;
    }

    void registrar(SseEmitter emitter) {
        connections.add(emitter);
        emitter.onCompletion(() -> connections.remove(emitter));
        emitter.onTimeout(() -> { connections.remove(emitter); emitter.complete(); });
        emitter.onError(error -> connections.remove(emitter));
    }

    @Scheduled(fixedDelay = 5000)
    public void sinalizar() {
        connections.forEach(this::enviar);
    }

    private void enviar(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("sync").data("pedidos"));
        } catch (Exception error) {
            connections.remove(emitter);
            emitter.completeWithError(error);
        }
    }
}
