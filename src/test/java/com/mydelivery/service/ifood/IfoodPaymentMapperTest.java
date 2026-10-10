package com.mydelivery.service.ifood;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mydelivery.model.Pedido;

class IfoodPaymentMapperTest {
    @Test void mapsOnlineCreditCardAsCreditAndPaid() {
        var result = IfoodPaymentMapper.mapear(Map.of("payments", Map.of(
                "prepaid", 49.90,
                "pending", 0,
                "methods", List.of(Map.of("type", "ONLINE", "method", "CREDIT")))));

        assertEquals(Pedido.FormaPagamento.CARTAO_CREDITO, result.forma());
        assertEquals(Pedido.ModoPagamento.ONLINE, result.modo());
        org.junit.jupiter.api.Assertions.assertTrue(result.pago());
    }

    @Test void mapsOfflineDebitAndLeavesOrderUnpaidForPaymentAtDelivery() {
        var result = IfoodPaymentMapper.mapear(Map.of("payments", Map.of(
                "prepaid", 0,
                "pending", 35.00,
                "methods", List.of(Map.of("type", "OFFLINE", "method", "DEBIT")))));

        assertEquals(Pedido.FormaPagamento.CARTAO_DEBITO, result.forma());
        assertEquals(Pedido.ModoPagamento.NA_ENTREGA, result.modo());
        org.junit.jupiter.api.Assertions.assertFalse(result.pago());
    }

    @Test void usesOfflineMethodForMixedOnlineAndDeliveryPayment() {
        var result = IfoodPaymentMapper.mapear(Map.of("payments", Map.of(
                "prepaid", 10.00,
                "pending", 20.00,
                "methods", List.of(
                        Map.of("type", "ONLINE", "method", "CREDIT"),
                        Map.of("type", "OFFLINE", "method", "CASH")))));

        assertEquals(Pedido.FormaPagamento.DINHEIRO, result.forma());
        assertEquals(Pedido.ModoPagamento.NA_ENTREGA, result.modo());
        org.junit.jupiter.api.Assertions.assertFalse(result.pago());
    }

    @Test void unknownPaymentMethodDoesNotGuessPix() {
        var result = IfoodPaymentMapper.mapear(Map.of("payments", Map.of(
                "prepaid", 15.00,
                "pending", 0,
                "methods", List.of(Map.of("type", "ONLINE", "method", "MEAL_VOUCHER")))));

        assertEquals(Pedido.FormaPagamento.PENDENTE, result.forma());
        assertEquals(Pedido.ModoPagamento.ONLINE, result.modo());
        org.junit.jupiter.api.Assertions.assertTrue(result.pago());
    }
}
