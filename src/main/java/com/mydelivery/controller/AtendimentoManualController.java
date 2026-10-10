package com.mydelivery.controller;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.whatsapp.AtendimentoManualService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/restaurante/whatsapp/solicitacoes-atendimento")
@PreAuthorize("hasAnyRole('RESTAURANTE','ADMIN')")
@RequiredArgsConstructor
public class AtendimentoManualController {
    private final RestauranteRepository restaurantes;
    private final AtendimentoManualService service;
    private Long loja(String email) {
        return restaurantes.findByUsuarioEmail(email).orElseThrow().getId();
    }
    @GetMapping
    public List<AtendimentoManualService.Alerta> listar(@AuthenticationPrincipal String email) {
        return service.pendentes(loja(email));
    }
    @PostMapping("/{id}/visualizar")
    public ResponseEntity<Void> visualizar(@AuthenticationPrincipal String email,@PathVariable long id) {
        service.visualizar(loja(email),id);
        return ResponseEntity.noContent().build();
    }
}
