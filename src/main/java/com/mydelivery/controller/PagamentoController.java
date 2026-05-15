package com.mydelivery.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.dto.pagamento.PagarCartaoRequest;
import com.mydelivery.dto.pagamento.PagarPixRequest;
import com.mydelivery.model.Pagamento;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.PagamentoService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class PagamentoController {

    private final PagamentoService pagamentoService;
    private final RestauranteRepository restauranteRepository;

    /**
     * Cliente final: consulta status do pagamento de um pedido.
     * Usado pra polling no front (modal do PIX fica aberto até virar APROVADO).
     */
    @GetMapping("/api/pagamentos/pedido/{pedidoId}")
    public ResponseEntity<Map<String, Object>> obterPorPedido(@PathVariable Long pedidoId) {
        Pagamento p = pagamentoService.obterPorPedido(pedidoId);
        return ResponseEntity.ok(montarResposta(p));
    }

    /**
     * Cliente final: cria cobrança PIX no MP e devolve QR Code + copia-e-cola.
     * Idempotente — chamadas repetidas pro mesmo pedido devolvem o mesmo pagamento.
     */
    @PostMapping("/api/pagamentos/pix")
    public ResponseEntity<Map<String, Object>> pagarPix(@Valid @RequestBody PagarPixRequest req) {
        Pagamento p = pagamentoService.criarPagamentoPixOnline(req);
        return ResponseEntity.ok(montarResposta(p));
    }

    /**
     * Cliente final: processa pagamento com cartão.
     * cardToken já foi gerado no browser pelo SDK do MP — backend nunca vê PAN/CVV.
     */
    @PostMapping("/api/pagamentos/cartao")
    public ResponseEntity<Map<String, Object>> pagarCartao(@Valid @RequestBody PagarCartaoRequest req) {
        Pagamento p = pagamentoService.processarCartao(req);
        return ResponseEntity.ok(montarResposta(p));
    }

    private Map<String, Object> montarResposta(Pagamento p) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("id", p.getId());
        resp.put("pedidoId", p.getPedido().getId());
        resp.put("metodo", p.getMetodo().name());
        resp.put("valor", p.getValor());
        resp.put("status", p.getStatus().name());
        resp.put("pixBrCode", p.getPixBrCode() != null ? p.getPixBrCode() : "");
        resp.put("pixQrBase64", p.getPixQrBase64() != null ? p.getPixQrBase64() : "");
        resp.put("pixChave", p.getPixChave() != null ? p.getPixChave() : "");
        resp.put("expiraEm", p.getExpiraEm() != null ? p.getExpiraEm().toString() : "");
        resp.put("mpStatusDetail", p.getMpStatusDetail() != null ? p.getMpStatusDetail() : "");
        resp.put("cartaoFinal", p.getCartaoFinal() != null ? p.getCartaoFinal() : "");
        resp.put("aprovadoEm", p.getAprovadoEm() != null ? p.getAprovadoEm().toString() : "");
        return resp;
    }

    /**
     * Admin: confirma pagamento manualmente (após ver PIX cair na conta).
     */
    @PostMapping("/api/restaurante/pedidos/{pedidoId}/confirmar-pagamento")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> confirmar(
            @AuthenticationPrincipal String email,
            @PathVariable Long pedidoId) {
        var r = restauranteRepository.findByUsuarioEmail(email).orElseThrow();
        Pagamento p = pagamentoService.confirmar(pedidoId, r.getId());
        return ResponseEntity.ok(Map.of(
                "status", p.getStatus().name(),
                "aprovadoEm", p.getAprovadoEm() != null ? p.getAprovadoEm().toString() : ""
        ));
    }
}
