package com.mydelivery.controller;

import com.mydelivery.dto.entregador.EntregadorLoginRequest;
import com.mydelivery.model.Entregador;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.EntregadorAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Login do entregador (PIN).
 *
 * Endpoint público — não exige Bearer. Emite JWT com role ENTREGADOR
 * usado depois nos endpoints /api/entregador/** (app mobile).
 *
 * Resposta UNAUTHORIZED é genérica em qualquer falha pra não vazar nem
 * se o restaurante existe nem se o PIN é parcialmente correto.
 */
@RestController
@RequiredArgsConstructor
public class EntregadorAuthController {

    private final RestauranteRepository restauranteRepository;
    private final EntregadorAuthService entregadorAuthService;

    @PostMapping("/api/entregador/{slugRestaurante}/login")
    public ResponseEntity<Map<String, Object>> login(
            @PathVariable String slugRestaurante,
            @RequestBody EntregadorLoginRequest body) {
        // Resolve restaurante. Não usa orElseThrow 404 pra não vazar existência.
        Restaurante r = restauranteRepository.findBySlug(slugRestaurante).orElse(null);
        if (r == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "PIN incorreto");
        }
        String pin = body == null ? null : body.getPin();
        Optional<Entregador> opt = entregadorAuthService.autenticar(r.getId(), pin);
        if (opt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "PIN incorreto");
        }
        Entregador e = opt.get();
        String token = entregadorAuthService.gerarToken(e);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("entregadorId", e.getId());
        resp.put("nome", e.getNome());
        resp.put("restauranteSlug", r.getSlug());
        resp.put("restauranteNome", r.getNome());
        return ResponseEntity.ok(resp);
    }
}
