package com.mydelivery.controller;

import com.mydelivery.dto.pedido.AtualizarStatusRequest;
import com.mydelivery.model.Pedido;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.service.PedidoService;
import com.mydelivery.service.RastreamentoEntregaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/** Exceção de entrega apenas para administrador autenticado, sempre com motivo e auditoria. */
@RestController
@RequiredArgsConstructor
public class RastreamentoEntregaAdminController {
    private final PedidoRepository pedidos;
    private final PedidoService pedidoService;
    private final RastreamentoEntregaService rastreamento;

    @PostMapping("/api/admin/rastreamento/pedido/{pedidoId}/confirmar-entrega")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> confirmar(@AuthenticationPrincipal String administrador, @PathVariable Long pedidoId, @RequestBody Map<String,String> body) {
        String motivo = body == null ? null : body.get("motivo");
        if (motivo == null || motivo.trim().length() < 10 || motivo.length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe um motivo com pelo menos 10 caracteres");
        Pedido p = pedidos.findById(pedidoId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
        if (p.getStatus() != Pedido.Status.SAIU_ENTREGA || p.getTipo() != Pedido.Tipo.DELIVERY)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pedido não está em rota de delivery");
        if (!Boolean.TRUE.equals(p.getRestaurante().getEntregaCodigoConfirmacaoAtivo()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A confirmação por código está desativada nesta loja");
        rastreamento.registrarOverride(p, motivo.trim(), administrador);
        AtualizarStatusRequest status = new AtualizarStatusRequest(); status.setStatus(Pedido.Status.ENTREGUE);
        return ResponseEntity.ok(pedidoService.atualizarStatus(p.getRestaurante().getId(), pedidoId, status));
    }
}
