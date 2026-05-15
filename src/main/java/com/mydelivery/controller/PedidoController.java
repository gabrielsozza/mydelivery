package com.mydelivery.controller;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.mydelivery.dto.pedido.*;
import com.mydelivery.model.Pedido;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.PedidoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class PedidoController {
    private final PedidoService pedidoService;
    private final RestauranteRepository restauranteRepository;

    @PostMapping("/pedidos/novo")
    public ResponseEntity<PedidoResponse> criarPedido(@Valid @RequestBody NovoPedidoRequest request) {
        return ResponseEntity.ok(pedidoService.criarPedido(request));
    }
    @GetMapping("/pedidos/{id}/acompanhar")
    public ResponseEntity<PedidoResponse> acompanharPedido(@PathVariable Long id) {
        return ResponseEntity.ok(pedidoService.acompanharPedido(id));
    }
    @GetMapping("/restaurante/{slug}/pedidos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<PedidoResponse>> listarPedidos(@AuthenticationPrincipal String email,
            @PathVariable String slug, @RequestParam(required=false) Pedido.Status status) {
        Long rid = getRestauranteId(email);
        return ResponseEntity.ok(status!=null ? pedidoService.listarPorStatus(rid,status) : pedidoService.listarPedidos(rid));
    }
    @GetMapping("/restaurante/{slug}/pedidos/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<PedidoResponse> buscarPedido(@AuthenticationPrincipal String email,
            @PathVariable String slug, @PathVariable Long id) {
        return ResponseEntity.ok(pedidoService.buscarPorId(getRestauranteId(email), id));
    }
    @PatchMapping("/restaurante/{slug}/pedidos/{id}/status")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<PedidoResponse> atualizarStatus(@AuthenticationPrincipal String email,
            @PathVariable String slug, @PathVariable Long id, @Valid @RequestBody AtualizarStatusRequest request) {
        return ResponseEntity.ok(pedidoService.atualizarStatus(getRestauranteId(email), id, request));
    }
    @PutMapping("/restaurante/{slug}/pedidos/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<PedidoResponse> editarPedido(@AuthenticationPrincipal String email,
            @PathVariable String slug, @PathVariable Long id, @Valid @RequestBody EditarPedidoRequest request) {
        return ResponseEntity.ok(pedidoService.editarPedido(getRestauranteId(email), id, request));
    }
    @PatchMapping("/restaurante/{slug}/pedidos/{id}/entregador")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<PedidoResponse> atribuirEntregador(@AuthenticationPrincipal String email,
            @PathVariable String slug, @PathVariable Long id, @RequestBody AtribuirEntregadorRequest request) {
        return ResponseEntity.ok(pedidoService.atribuirEntregador(getRestauranteId(email), id, request));
    }
    private Long getRestauranteId(String email) {
        return restauranteRepository.findByUsuarioEmail(email)
                .orElseThrow(() -> new RuntimeException("Restaurante nao encontrado")).getId();
    }
}
