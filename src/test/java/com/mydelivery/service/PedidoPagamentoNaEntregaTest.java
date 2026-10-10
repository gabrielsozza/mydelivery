package com.mydelivery.service;

import com.mydelivery.model.Pedido;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PedidoPagamentoNaEntregaTest {
    @Test void deliveryWithDeclaredInPersonPaymentRecordsReceipt() {
        Pedido order = Pedido.builder().modoPagamento(Pedido.ModoPagamento.NA_ENTREGA)
                .formaPagamento(Pedido.FormaPagamento.CARTAO_DEBITO).pago(false).build();
        PedidoService.registrarPagamentoPresencialNaEntrega(order, Pedido.Status.ENTREGUE);
        assertTrue(order.getPago());
        assertNotNull(order.getPagoEm());
    }

    @Test void pendingAndOnlinePaymentsDoNotBecomePaidOnDelivery() {
        for (Pedido.ModoPagamento mode : Pedido.ModoPagamento.values()) {
            Pedido order = Pedido.builder().modoPagamento(mode)
                    .formaPagamento(mode == Pedido.ModoPagamento.ONLINE
                            ? Pedido.FormaPagamento.PIX : Pedido.FormaPagamento.PENDENTE)
                    .pago(false).build();
            PedidoService.registrarPagamentoPresencialNaEntrega(order, Pedido.Status.ENTREGUE);
            assertFalse(order.getPago());
        }
    }
}
