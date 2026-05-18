package com.mydelivery.controller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.model.ChamadaGarcom;
import com.mydelivery.model.Mesa;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.ChamadaGarcomRepository;
import com.mydelivery.repository.MesaRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ChamadaGarcomController {

    private final ChamadaGarcomRepository chamadaRepo;
    private final MesaRepository mesaRepo;
    private final RestauranteRepository restauranteRepo;

    /** Throttle: novo chamado da mesma mesa só após N segundos (evita spam do botão). */
    private static final int THROTTLE_SEGUNDOS = 30;

    /**
     * Endpoint PÚBLICO: cliente da mesa toca em "Chamar Garçom".
     * Body: { slugRestaurante: "...", slugMesa: "mesa-01", nomeCliente: "João" (opcional) }
     */
    @PostMapping("/public/chamar-garcom")
    @Transactional
    public ResponseEntity<Map<String, Object>> chamar(@RequestBody Map<String, String> body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String slugRest = body.get("slugRestaurante");
        String slugMesa = body.get("slugMesa");
        String nome = body.get("nomeCliente");

        if (slugRest == null || slugMesa == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slugRestaurante e slugMesa são obrigatórios");
        }
        Mesa mesa = mesaRepo.findByRestauranteSlugAndSlug(slugRest, slugMesa)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mesa não encontrada"));

        // Anti-spam: se já tem PENDENTE recente da mesma mesa, retorna a existente
        var existente = chamadaRepo.findFirstByMesaIdAndStatusOrderByCriadaEmDesc(mesa.getId(), "PENDENTE");
        if (existente.isPresent()
                && Duration.between(existente.get().getCriadaEm(), LocalDateTime.now()).getSeconds() < THROTTLE_SEGUNDOS) {
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "ja_chamado", true,
                    "id", existente.get().getId()
            ));
        }

        ChamadaGarcom c = chamadaRepo.save(ChamadaGarcom.builder()
                .restaurante(mesa.getRestaurante())
                .mesa(mesa)
                .nomeCliente(nome)
                .status("PENDENTE")
                .build());
        log.info("[Garcom] Chamada criada — restaurante={} mesa={} cliente={}",
                mesa.getRestaurante().getId(), mesa.getSlug(), nome);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "ok", true,
                "id", c.getId()
        ));
    }

    /** Painel do dono: lista chamadas PENDENTES pra mostrar + tocar som. */
    @GetMapping("/api/chamadas-garcom/pendentes")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> pendentes(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(chamadaRepo
                .findByRestauranteIdAndStatusOrderByCriadaEmAsc(r.getId(), "PENDENTE")
                .stream()
                .map(this::serializar)
                .toList());
    }

    /** Dispensa (marca atendida). */
    @DeleteMapping("/api/chamadas-garcom/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional
    public ResponseEntity<Void> dispensar(@AuthenticationPrincipal String email, @PathVariable Long id) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        ChamadaGarcom c = chamadaRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!c.getRestaurante().getId().equals(r.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        c.setStatus("ATENDIDA");
        c.setAtendidaEm(LocalDateTime.now());
        chamadaRepo.save(c);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> serializar(ChamadaGarcom c) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", c.getId());
        out.put("mesaNome", c.getMesa() != null ? c.getMesa().getNome() : null);
        out.put("mesaSlug", c.getMesa() != null ? c.getMesa().getSlug() : null);
        out.put("nomeCliente", c.getNomeCliente());
        out.put("criadaEm", c.getCriadaEm() != null ? c.getCriadaEm().toString() : null);
        return out;
    }
}
