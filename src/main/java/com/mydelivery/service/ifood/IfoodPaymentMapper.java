package com.mydelivery.service.ifood;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mydelivery.model.Pedido;

/** Converts the payment section of an iFood Order API response without guessing. */
final class IfoodPaymentMapper {
    private IfoodPaymentMapper() {}

    record Details(Pedido.FormaPagamento forma, Pedido.ModoPagamento modo, boolean pago) {}

    static Details mapear(Map<String, Object> order) {
        Map<String, Object> payments = mapOf(order.get("payments"));
        BigDecimal prepaid = decimal(payments.get("prepaid"));
        BigDecimal pending = decimal(payments.get("pending"));
        boolean temPendente = pending.signum() > 0;

        List<?> methods = payments.get("methods") instanceof List<?> list ? list : List.of();
        Map<String, Object> escolhido = escolherMetodo(methods, temPendente);
        Pedido.FormaPagamento forma = escolhido == null
                ? Pedido.FormaPagamento.PENDENTE
                : mapearForma(text(escolhido.get("method")), mapOf(escolhido.get("wallet")), mapOf(escolhido.get("card")));

        String type = escolhido == null ? "" : norm(escolhido.get("type"));
        Pedido.ModoPagamento modo;
        if (temPendente || "OFFLINE".equals(type)) {
            modo = Pedido.ModoPagamento.NA_ENTREGA;
        } else if ("ONLINE".equals(type) || prepaid.signum() > 0) {
            modo = Pedido.ModoPagamento.ONLINE;
        } else {
            modo = null;
        }
        // Mixed payments with a remaining balance are not fully paid.
        boolean pago = modo == Pedido.ModoPagamento.ONLINE && !temPendente;
        return new Details(forma, modo, pago);
    }

    private static Map<String, Object> escolherMetodo(List<?> methods, boolean temPendente) {
        Map<String, Object> primeiro = null;
        for (Object value : methods) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> method = castMap(raw);
            if (primeiro == null) primeiro = method;
            if (temPendente && "OFFLINE".equals(norm(method.get("type")))) return method;
        }
        return primeiro;
    }

    private static Pedido.FormaPagamento mapearForma(String method, Map<String, Object> wallet, Map<String, Object> card) {
        return switch (method == null ? "" : method.trim().toUpperCase(Locale.ROOT)) {
            case "CASH" -> Pedido.FormaPagamento.DINHEIRO;
            case "CREDIT" -> Pedido.FormaPagamento.CARTAO_CREDITO;
            case "DEBIT" -> Pedido.FormaPagamento.CARTAO_DEBITO;
            case "PIX" -> Pedido.FormaPagamento.PIX;
            case "DIGITAL_WALLET" -> {
                String walletName = norm(wallet.get("name"));
                if (walletName.contains("APPLE_PAY")) yield Pedido.FormaPagamento.APPLE_PAY;
                if (card.get("brand") != null && !text(card.get("brand")).isBlank()) yield Pedido.FormaPagamento.CARTAO_CREDITO;
                yield Pedido.FormaPagamento.PENDENTE;
            }
            // These iFood methods do not have a matching MyDelivery payment enum.
            // Keep them explicitly unresolved instead of displaying a false PIX.
            default -> Pedido.FormaPagamento.PENDENTE;
        };
    }

    private static String norm(Object value) {
        return text(value).trim().toUpperCase(Locale.ROOT);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Number number) return new BigDecimal(number.toString());
        try { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
        catch (NumberFormatException ignored) { return BigDecimal.ZERO; }
    }

    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> raw ? castMap(raw) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> value) { return (Map<String, Object>) value; }
}
