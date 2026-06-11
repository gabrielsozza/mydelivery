package com.mydelivery.service.ifood;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.Pedido;
import com.mydelivery.model.PedidoItem;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orquestra os eventos da Order API do iFood:
 *  - Recebe evento (PLC/CFM/CAN/DSP/CON)
 *  - Busca detalhe do pedido na API
 *  - Cria/atualiza Pedido local com origem=IFOOD
 *  - Marca como pago (iFood já cobrou o cliente)
 *
 * Idempotente: se o mesmo orderId chega 2x (ACK perdido), reaproveita
 * o Pedido existente em vez de duplicar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IfoodService {

    private final IfoodClient client;
    private final RestauranteRepository restauranteRepo;
    private final PedidoRepository pedidoRepo;

    /**
     * Processa um evento do polling. Decide o que fazer com base no tipo.
     * Retorna true se o evento foi processado (pode ser ack-ado).
     */
    @Transactional
    public boolean processarEvento(Map<String, Object> evento) {
        String orderId = (String) evento.get("orderId");
        String code = (String) evento.get("code");
        String merchantId = (String) evento.get("merchantId");

        if (orderId == null || code == null) {
            log.warn("[iFood] Evento sem orderId/code — descartando: {}", evento.keySet());
            return true;
        }

        Restaurante restaurante = restauranteRepo.findByIfoodMerchantId(merchantId).orElse(null);
        if (restaurante == null) {
            log.warn("[iFood] merchantId={} não encontrado localmente — ignorando evento {}",
                    merchantId, code);
            return true; // ack — não é nosso, livra a fila do iFood
        }

        try {
            switch (code) {
                case "PLC":          // placed = pedido novo
                    return tratarPedidoNovo(restaurante, orderId);
                case "CFM":          // confirmed pelo restaurante (próprio ou iFood)
                case "RPR":          // ready for pickup
                case "DSP":          // dispatched
                case "CON":          // concluded (entregue)
                    return tratarMudancaStatus(restaurante, orderId, code);
                case "CAN":          // cancelled
                    return tratarCancelamento(restaurante, orderId);
                default:
                    log.info("[iFood] Evento {} ignorado (nao implementado)", code);
                    return true; // ack mesmo assim — não vamos tratar
            }
        } catch (Exception e) {
            log.error("[iFood] Erro processando evento {} orderId={}: {}", code, orderId, e.getMessage(), e);
            return false; // NÃO ack — próximo poll tenta de novo
        }
    }

    /** Cria Pedido local a partir de um order detalhado do iFood. Idempotente. */
    private boolean tratarPedidoNovo(Restaurante r, String orderId) {
        // Já existe? (ack perdido em polling anterior) → reaproveita
        var existente = pedidoRepo.findByIfoodOrderId(orderId);
        if (existente.isPresent()) {
            log.info("[iFood] Pedido {} já existe localmente (duplicata de PLC) — ack", orderId);
            return true;
        }

        Map<String, Object> det = client.getOrderDetalhe(orderId);
        if (det == null || det.isEmpty()) {
            log.warn("[iFood] getOrderDetalhe vazio pra {}", orderId);
            return false;
        }

        Pedido p = new Pedido();
        p.setRestaurante(r);
        p.setOrigem(Pedido.Origem.IFOOD);
        p.setIfoodOrderId(orderId);
        p.setIfoodDisplayId(strOf(det.get("displayId")));
        p.setStatus(Pedido.Status.CONFIRMADO); // pedidos iFood já vêm pagos+confirmados
        p.setTipo(extrairTipo(det));
        p.setFormaPagamento(Pedido.FormaPagamento.PIX); // genérico — iFood não detalha
        p.setModoPagamento(Pedido.ModoPagamento.ONLINE);
        p.setPago(true);
        p.setPagoEm(LocalDateTime.now());

        // Customer
        Map<String, Object> customer = mapOf(det.get("customer"));
        p.setNomeChamada(strOf(customer.get("name")));

        // Delivery address
        Map<String, Object> delivery = mapOf(det.get("delivery"));
        Map<String, Object> address = mapOf(delivery.get("deliveryAddress"));
        p.setEnderecoEntrega(montarEndereco(address));

        // Total
        Map<String, Object> total = mapOf(det.get("total"));
        BigDecimal subtotal = decOf(total.get("subTotal"));
        BigDecimal taxa = decOf(total.get("deliveryFee"));
        BigDecimal totalPed = decOf(total.get("orderAmount"));
        p.setSubtotal(subtotal);
        p.setTaxaEntrega(taxa);
        p.setTotal(totalPed);

        // Itens
        List<PedidoItem> itens = new ArrayList<>();
        Object itemsObj = det.get("items");
        if (itemsObj instanceof List<?> itensList) {
            for (Object io : itensList) {
                if (!(io instanceof Map<?, ?> im)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> i = (Map<String, Object>) im;
                PedidoItem pi = new PedidoItem();
                pi.setPedido(p);
                pi.setNomeProduto(strOf(i.get("name")));
                Object qty = i.get("quantity");
                pi.setQuantidade(qty instanceof Number n ? n.intValue() : 1);
                BigDecimal unit = decOf(i.get("unitPrice"));
                pi.setPrecoUnitario(unit);
                pi.setSubtotal(unit.multiply(BigDecimal.valueOf(pi.getQuantidade())));
                // Observações + opções (complementos) viram texto na obs
                pi.setObservacao(montarObservacaoItem(i));
                itens.add(pi);
            }
        }
        p.setItens(itens);

        // Observação geral do pedido
        Object obs = det.get("observations");
        if (obs != null) p.setObservacao(obs.toString());

        pedidoRepo.save(p);
        log.info("[iFood] Pedido novo criado — local={} iFood={} (display={}) restaurante={}",
                p.getId(), orderId, p.getIfoodDisplayId(), r.getId());
        return true;
    }

    private boolean tratarMudancaStatus(Restaurante r, String orderId, String code) {
        var opt = pedidoRepo.findByIfoodOrderId(orderId);
        if (opt.isEmpty()) {
            log.warn("[iFood] {} pra orderId={} mas pedido não está local — buscando detalhe",
                    code, orderId);
            // Pode ser que perdemos o PLC — cria agora
            return tratarPedidoNovo(r, orderId);
        }
        Pedido p = opt.get();
        switch (code) {
            case "CFM" -> p.setStatus(Pedido.Status.CONFIRMADO);
            case "RPR" -> p.setStatus(Pedido.Status.PRONTO);
            case "DSP" -> p.setStatus(Pedido.Status.SAIU_ENTREGA);
            case "CON" -> p.setStatus(Pedido.Status.ENTREGUE);
        }
        pedidoRepo.save(p);
        log.info("[iFood] Status atualizado pra orderId={}: {}", orderId, code);
        return true;
    }

    private boolean tratarCancelamento(Restaurante r, String orderId) {
        var opt = pedidoRepo.findByIfoodOrderId(orderId);
        if (opt.isEmpty()) {
            log.info("[iFood] CAN sem pedido local pra orderId={} — ack mesmo assim", orderId);
            return true;
        }
        Pedido p = opt.get();
        p.setStatus(Pedido.Status.CANCELADO);
        pedidoRepo.save(p);
        log.info("[iFood] Pedido orderId={} cancelado", orderId);
        return true;
    }

    // ── helpers ──

    private Pedido.Tipo extrairTipo(Map<String, Object> det) {
        Map<String, Object> delivery = mapOf(det.get("delivery"));
        String mode = strOf(delivery.get("mode"));
        if (mode == null) return Pedido.Tipo.DELIVERY;
        return switch (mode.toUpperCase()) {
            case "TAKEOUT" -> Pedido.Tipo.RETIRADA;
            case "INDOOR"  -> Pedido.Tipo.BALCAO;
            default        -> Pedido.Tipo.DELIVERY;
        };
    }

    private String montarEndereco(Map<String, Object> a) {
        if (a == null || a.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        appendIfNotBlank(sb, strOf(a.get("streetName")));
        appendIfNotBlank(sb, strOf(a.get("streetNumber")));
        appendIfNotBlank(sb, strOf(a.get("neighborhood")));
        appendIfNotBlank(sb, strOf(a.get("city")));
        String complemento = strOf(a.get("complement"));
        if (complemento != null && !complemento.isBlank()) {
            sb.append(" (").append(complemento).append(")");
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private String montarObservacaoItem(Map<String, Object> item) {
        StringBuilder sb = new StringBuilder();
        Object obs = item.get("observations");
        if (obs != null && !obs.toString().isBlank()) sb.append(obs);
        // Options/complementos
        Object opts = item.get("options");
        if (opts instanceof List<?> optList && !optList.isEmpty()) {
            List<String> partes = new ArrayList<>();
            for (Object o : optList) {
                if (!(o instanceof Map<?, ?> om)) continue;
                Map<String, Object> opt = (Map<String, Object>) om;
                String nm = strOf(opt.get("name"));
                BigDecimal pr = decOf(opt.get("price"));
                if (nm == null) continue;
                if (pr != null && pr.compareTo(BigDecimal.ZERO) > 0) {
                    partes.add(nm + " (R$ " + pr.setScale(2, java.math.RoundingMode.HALF_UP)
                            .toPlainString().replace('.', ',') + ")");
                } else {
                    partes.add(nm);
                }
            }
            if (!partes.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("+ ").append(String.join(", ", partes));
            }
        }
        return sb.toString().isBlank() ? null : sb.toString();
    }

    private static void appendIfNotBlank(StringBuilder sb, String s) {
        if (s == null || s.isBlank()) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(s);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
    private static String strOf(Object o) { return o == null ? null : o.toString(); }
    private static BigDecimal decOf(Object o) {
        if (o == null) return BigDecimal.ZERO;
        try {
            if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            return new BigDecimal(o.toString());
        } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
