package com.mydelivery.controller;

import com.mydelivery.service.PedidoAlertStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequiredArgsConstructor
public class PedidoAlertStreamController {
    private final PedidoAlertStreamService service;

    @GetMapping(value = "/api/restaurante/pedidos/alertas-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('RESTAURANTE')")
    public SseEmitter conectar(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return service.abrir();
    }
}
