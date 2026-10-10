package com.mydelivery.controller;

import com.mydelivery.service.RastreamentoEntregaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RastreamentoEntregaController {
    private final RastreamentoEntregaService service;

    @GetMapping("/api/publico/rastreamento/{token}")
    public ResponseEntity<Map<String,Object>> consultar(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().mustRevalidate())
                .header("Pragma", "no-cache").body(service.consultar(token));
    }
}
