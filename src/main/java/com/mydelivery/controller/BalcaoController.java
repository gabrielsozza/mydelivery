package com.mydelivery.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.model.Pedido;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.BalcaoService;

import lombok.RequiredArgsConstructor;

/**
 * POS de balcão — usado pelo caixa (autenticado como RESTAURANTE).
 *
 *  POST  /api/restaurante/balcao/pedido            cria pedido + gera senha
 *  GET   /api/restaurante/balcao/fila              lista pedidos do dia em preparo
 *  PATCH /api/restaurante/balcao/pedido/{id}       muda status (PREPARO/PRONTO/ENTREGUE)
 *  GET   /api/restaurante/balcao/cliente?tel=X     memória do cliente (top 5 itens)
 */
@RestController
@RequiredArgsConstructor
public class BalcaoController {

    private final BalcaoService balcaoService;
    private final RestauranteRepository restauranteRepo;
    private final PedidoRepository pedidoRepo;

    @PostMapping("/api/restaurante/balcao/pedido")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> criar(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) body.get("itens");
        try {
            var resp = balcaoService.criarPedido(
                    r,
                    strOf(body.get("nomeChamada")),
                    strOf(body.get("telefoneCliente")),
                    itens,
                    strOf(body.get("observacao")),
                    strOf(body.get("formaPagamento")));
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/api/restaurante/balcao/fila")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> fila(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(balcaoService.fila(r.getId()));
    }

    @GetMapping("/api/restaurante/balcao/cliente")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> memoriaCliente(
            @AuthenticationPrincipal String email,
            @RequestParam(value = "tel", required = false) String tel) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(balcaoService.memoriaCliente(r.getId(), tel));
    }

    @PatchMapping("/api/restaurante/balcao/pedido/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> mudarStatus(
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        Pedido p = pedidoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!p.getRestaurante().getId().equals(r.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String novoStr = body == null ? null : body.get("status");
        if (novoStr == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status obrigatório");
        try {
            p.setStatus(Pedido.Status.valueOf(novoStr.toUpperCase()));
            if (p.getStatus() == Pedido.Status.ENTREGUE) {
                p.setPago(true);
                p.setPagoEm(java.time.LocalDateTime.now());
            }
            pedidoRepo.save(p);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status inválido");
        }
        return ResponseEntity.ok(Map.of("ok", true, "status", p.getStatus().name()));
    }

    private String strOf(Object o) { return o == null ? null : o.toString(); }
}
