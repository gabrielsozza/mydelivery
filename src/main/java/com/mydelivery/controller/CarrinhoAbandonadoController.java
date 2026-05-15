package com.mydelivery.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.dto.carrinho.CarrinhoAbandonadoRequest;
import com.mydelivery.dto.carrinho.CarrinhoAbandonadoResponse;
import com.mydelivery.service.CarrinhoAbandonadoService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/carrinho")
@RequiredArgsConstructor
public class CarrinhoAbandonadoController {

    private final CarrinhoAbandonadoService carrinhoService;

    // Cardápio público — salva/atualiza carrinho (sem token)
    @PostMapping
    public ResponseEntity<Void> salvarCarrinho(@RequestBody CarrinhoAbandonadoRequest req) {
        carrinhoService.salvarOuAtualizar(req);
        return ResponseEntity.ok().build();
    }

    // Cardápio público — pedido confirmado, marca como recuperado (sem token)
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> marcarRecuperado(@PathVariable String sessionId) {
        carrinhoService.marcarComoRecuperado(sessionId);
        return ResponseEntity.ok().build();
    }

    // Painel do restaurante — lista carrinhos (requer token)
    @GetMapping("/restaurante/{slug}")
    public ResponseEntity<List<CarrinhoAbandonadoResponse>> listar(@PathVariable String slug) {
        return ResponseEntity.ok(carrinhoService.listarPorRestaurante(slug));
    }

    // Painel — WhatsApp enviado manualmente, marca como notificado (requer token)
    @PatchMapping("/{id}/notificar")
    public ResponseEntity<Void> notificar(@PathVariable Long id) {
        carrinhoService.notificar(id);
        return ResponseEntity.ok().build();
    }

    // Painel — arquivar carrinho (requer token)
    @PatchMapping("/{id}/arquivar")
    public ResponseEntity<Void> arquivar(@PathVariable Long id) {
        carrinhoService.arquivar(id);
        return ResponseEntity.ok().build();
    }
}
