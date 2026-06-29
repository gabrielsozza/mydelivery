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
    /**
     * Lista pedidos do restaurante.
     *
     * Otimização de estabilidade (auditoria 2026-06-28):
     *  - Antes: SEMPRE retornava TODOS os pedidos históricos (sem filtro).
     *    Restaurante com 6 meses tinha 5k+ pedidos × polling de 5s do
     *    pedidos-alert.js = 80KB × 12 req/min × N abas. Gerava OOM em pico.
     *  - Agora: aceita ?desde=ISO_INSTANT (ex: 2026-06-27T00:00:00Z).
     *    Sem param: default 30 dias atrás. Frontend filtra client-side
     *    por período menor; admin/relatorio pode passar data mais antiga.
     *  - pedidos-alert.js passa desde=-6h pra detectar pedidos novos
     *    rapidamente sem carregar histórico inteiro.
     */
    @GetMapping("/restaurante/{slug}/pedidos")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<PedidoResponse>> listarPedidos(@AuthenticationPrincipal String email,
            @PathVariable String slug,
            @RequestParam(required=false) Pedido.Status status,
            @RequestParam(required=false) String desde) {
        Long rid = getRestauranteId(email);
        if (status != null) {
            return ResponseEntity.ok(pedidoService.listarPorStatus(rid, status));
        }
        // Parse defensivo do ?desde — aceita ISO instant ou ISO local datetime
        java.time.LocalDateTime desdeLdt = parseDesde(desde);
        return ResponseEntity.ok(pedidoService.listarPedidos(rid, desdeLdt));
    }

    private java.time.LocalDateTime parseDesde(String desde) {
        if (desde == null || desde.isBlank()) {
            // Default seguro: 30 dias atrás. Restaurante normal não precisa
            // ver mais que isso no painel diário — relatórios usam endpoint
            // próprio com filtro explícito.
            return java.time.LocalDateTime.now().minusDays(30);
        }
        try {
            // Aceita "2026-06-27T00:00:00Z" (Instant)
            return java.time.Instant.parse(desde).atZone(java.time.ZoneId.of("America/Sao_Paulo")).toLocalDateTime();
        } catch (Exception ignore) {}
        try {
            // Aceita "2026-06-27T00:00:00"
            return java.time.LocalDateTime.parse(desde);
        } catch (Exception ignore) {}
        // Inválido — usa default
        return java.time.LocalDateTime.now().minusDays(30);
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
