package com.mydelivery.service.ifood;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.config.IfoodProperties;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.PedidoItem;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;

import jakarta.annotation.PreDestroy;
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
    private final IfoodProperties props;

    /**
     * Executor pra disparar auto-cancel atrasado em modo homologação.
     * Pool de 2 threads é suficiente pois homologação testa 1 pedido por vez.
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "ifood-homolog-cancel");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdown() { scheduler.shutdownNow(); }

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
                case "CAN":          // cancelled (efetivo)
                    return tratarCancelamento(restaurante, orderId);
                case "CCR":          // cancellation requested PELO CLIENTE — precisamos aceitar
                case "CANCELLATION_REQUESTED":
                    return tratarCancelamentoSolicitadoPeloCliente(restaurante, orderId);
                case "CRA":          // cancellation request accepted (eco do aceite)
                case "CRD":          // cancellation request denied
                case "REC":          // received (legado)
                case "KEEPALIVE":    // ping do iFood — só ack
                    log.debug("[iFood] Evento {} ack-only (sem ação local)", code);
                    return true;
                default:
                    log.info("[iFood] Evento {} ignorado (nao implementado) — orderId={}", code, orderId);
                    return true; // ack mesmo assim — não vamos tratar
            }
        } catch (Exception e) {
            log.error("[iFood] Erro processando evento {} orderId={}: {}", code, orderId, e.getMessage(), e);
            return false; // NÃO ack — próximo poll tenta de novo
        }
    }

    /**
     * CCR — cliente pediu cancelamento pelo app iFood. Precisamos responder
     * em ~10min com acceptCancellation OU denyCancellation, senão o iFood
     * penaliza o restaurante.
     *
     * Política: AUTO-ACEITAR. Como pedidos iFood vêm pagos pelo cliente
     * e cancelar é prejuízo zero (estorno automático), preferimos sempre
     * aceitar a vontade do cliente. Manda o accept e marca local como
     * CANCELADO (o iFood vai emitir CAN logo depois confirmando).
     */
    private boolean tratarCancelamentoSolicitadoPeloCliente(Restaurante r, String orderId) {
        log.info("[iFood-AUDIT] event=CCR orderId={} merchantId={} action=auto_accept_start",
                orderId, r.getIfoodMerchantId());
        // 1) Aceita no iFood primeiro (fail-fast — sem accept não cancela)
        try {
            client.aceitarCancelamento(orderId);
            log.info("[iFood-AUDIT] event=CCR orderId={} action=acceptCancellation_sent_ok", orderId);
        } catch (Exception e) {
            log.error("[iFood-AUDIT] event=CCR orderId={} action=acceptCancellation_FAILED erro={}",
                    orderId, e.getMessage());
            return false; // retenta no próximo poll
        }
        // 2) Atualiza local — se o pedido ainda nem foi criado localmente,
        //    materializa via getOrderDetalhe pra Firefly Audit detectar reflexo.
        var opt = pedidoRepo.findByIfoodOrderId(orderId);
        if (opt.isPresent()) {
            Pedido p = opt.get();
            Pedido.Status statusAntigo = p.getStatus();
            p.setStatus(Pedido.Status.CANCELADO);
            pedidoRepo.save(p);
            log.info("[iFood-AUDIT] event=CCR orderId={} statusAntigo={} statusNovo=CANCELADO action=local_update_ok",
                    orderId, statusAntigo);
        } else {
            // Materializa direto (não tem PLC) — mesma proteção do tratarCancelamento
            try { criarPedidoLocalCancelado(r, orderId); }
            catch (Exception e) {
                log.warn("[iFood-AUDIT] event=CCR orderId={} materialize_skipped erro={}", orderId, e.getMessage());
            }
        }
        return true;
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

        // Delivery address + agendamento (deliveryDateTime futuro = pedido agendado)
        Map<String, Object> delivery = mapOf(det.get("delivery"));
        Map<String, Object> address = mapOf(delivery.get("deliveryAddress"));
        p.setEnderecoEntrega(montarEndereco(address));
        LocalDateTime agendadoPara = extrairAgendamento(delivery);
        if (agendadoPara != null) {
            p.setAgendadoPara(agendadoPara);
            log.info("[iFood-AUDIT] event=PLC orderId={} scheduled=true deliveryDateTime={}",
                    orderId, agendadoPara);
        }

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

        // ── AUTO-CONFIRM IMEDIATO ──────────────────────────────────────────
        // Setamos status=CONFIRMADO localmente, mas o iFood SÓ considera o
        // pedido "Confirmado" quando recebe POST /orders/{id}/confirm.
        // Sem isso o cenário "Pedido Confirmado" da homologação reprova com
        // 'O pedido foi criado, mas não foi confirmado pelo restaurante'.
        //
        // Como auto-aceitamos todo pedido iFood (já vem pago, sem revisão),
        // mandamos o confirm AGORA. SLA do iFood é 10s — qualquer atraso
        // vira penalização. Idempotente: iFood aceita confirm duplicado.
        //
        // Fail-safe: erro de rede não bloqueia criação do pedido local
        // (restaurante já vê no painel; confirm pode ser reenviado depois).
        try {
            client.confirmar(orderId);
            log.info("[iFood] confirm enviado pra orderId={} (auto após PLC)", orderId);
        } catch (Exception e) {
            log.error("[iFood] auto-confirm FALHOU pra orderId={}: {}", orderId, e.getMessage());
        }

        // ── HOMOLOGAÇÃO: auto-cancel ─────────────────────────────────────
        // Cenário "Pedido Cancelado" do TOQAN exige que o restaurante envie
        // requestCancellation pro iFood DENTRO da janela de teste. Como o
        // TOQAN é automatizado e não há intervenção humana, sem auto-cancel
        // o teste expira e reprova com "logs de cancelamento não registrados".
        //
        // Política em modo homologação: agenda cancel pra 45s após PLC.
        // Tempo suficiente pra o iFood considerar o confirm válido + auditar
        // o ciclo completo PLC → CFM → REQ-CANCEL → CAN.
        //
        // Em produção real (homologacaoMode=false), nada acontece — restaurante
        // cancela manual pelo painel quando quiser.
        if (props.isHomologacaoMode()) {
            agendarAutoCancelHomologacao(orderId, props.getHomologacaoAutoCancelDelaySec());
        }
        return true;
    }

    /**
     * Agenda envio de requestCancellation N segundos no futuro. Usado SÓ
     * em modo homologação. Fail-safe: erro silencioso (não bloqueia nada).
     */
    private void agendarAutoCancelHomologacao(String orderId, int delaySec) {
        log.info("[iFood-AUDIT] event=PLC orderId={} action=auto_cancel_scheduled delaySec={}",
                orderId, delaySec);
        scheduler.schedule(() -> {
            try {
                log.info("[iFood-AUDIT] event=AUTO_CANCEL orderId={} action=sending_requestCancellation reason='Homologacao iFood — auto cancel'",
                        orderId);
                client.cancelar(orderId, "501", "Homologacao iFood - cancelamento automatico para teste");
                log.info("[iFood-AUDIT] event=AUTO_CANCEL orderId={} action=requestCancellation_sent_ok", orderId);
            } catch (Exception e) {
                log.error("[iFood-AUDIT] event=AUTO_CANCEL orderId={} action=requestCancellation_FAILED erro={}",
                        orderId, e.getMessage());
            }
        }, delaySec, TimeUnit.SECONDS);
    }

    /**
     * Parser do agendamento iFood. Vem em "delivery.deliveryDateTime" como
     * ISO 8601 com timezone (ex: "2026-06-28T15:30:00.000-03:00").
     * Converte pra LocalDateTime no fuso de São Paulo (timezone da operação).
     */
    private LocalDateTime extrairAgendamento(Map<String, Object> delivery) {
        if (delivery == null || delivery.isEmpty()) return null;
        String dt = strOf(delivery.get("deliveryDateTime"));
        if (dt == null || dt.isBlank()) return null;
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(dt);
            return zdt.withZoneSameInstant(ZoneId.of("America/Sao_Paulo")).toLocalDateTime();
        } catch (DateTimeParseException e) {
            log.warn("[iFood] deliveryDateTime mal formatado '{}': {}", dt, e.getMessage());
            return null;
        }
    }

    private boolean tratarMudancaStatus(Restaurante r, String orderId, String code) {
        var opt = pedidoRepo.findByIfoodOrderId(orderId);
        if (opt.isEmpty()) {
            log.warn("[iFood-AUDIT] {} pra orderId={} mas pedido não está local — buscando detalhe",
                    code, orderId);
            // Pode ser que perdemos o PLC — cria agora
            return tratarPedidoNovo(r, orderId);
        }
        Pedido p = opt.get();
        Pedido.Status statusAntigo = p.getStatus();
        switch (code) {
            case "CFM" -> p.setStatus(Pedido.Status.CONFIRMADO);
            case "RPR" -> p.setStatus(Pedido.Status.PRONTO);
            case "DSP" -> p.setStatus(Pedido.Status.SAIU_ENTREGA);
            case "CON" -> p.setStatus(Pedido.Status.ENTREGUE);
        }
        pedidoRepo.save(p);
        log.info("[iFood-AUDIT] event={} orderId={} merchantId={} statusAntigo={} statusNovo={} action=local_update_ok",
                code, orderId, r.getIfoodMerchantId(), statusAntigo, p.getStatus());
        return true;
    }

    /**
     * CAN — pedido cancelado. Pode chegar em 3 situações:
     *  1. Cliente cancelou pelo app iFood + nós aceitamos (CCR antes)
     *  2. Restaurante cancelou via nosso painel (nós enviamos requestCancellation)
     *  3. iFood cancelou direto (timeout, fraude, etc) — sem CCR antes
     *
     * IMPORTANTE: se o CAN chega pra um orderId que não temos local (cenário
     * da homologação: pedido cancelado antes do nosso polling pegar o PLC),
     * SEMPRE precisamos materializar localmente — buscando o detalhe via API
     * e salvando como CANCELADO. Sem isso, o Firefly Audit do iFood detecta
     * que demos ACK mas não houve reflexo local → homologação reprova com
     * "logs de cancelamento não foram registrados corretamente".
     */
    private boolean tratarCancelamento(Restaurante r, String orderId) {
        var opt = pedidoRepo.findByIfoodOrderId(orderId);
        if (opt.isEmpty()) {
            log.warn("[iFood-AUDIT] CAN sem pedido local pra orderId={} — materializando como CANCELADO", orderId);
            // Cria o pedido local marcado como CANCELADO direto. Reusa o
            // tratarPedidoNovo pra puxar detalhe (cliente, endereço, total)
            // e logo depois força CANCELADO + skipa o auto-confirm.
            try {
                boolean criado = criarPedidoLocalCancelado(r, orderId);
                if (criado) return true;
            } catch (Exception e) {
                log.error("[iFood-AUDIT] FALHA ao materializar CAN pra {}: {}", orderId, e.getMessage());
            }
            // Mesmo se falhou ao buscar detalhe, dá ACK pra não ficar em loop
            // (iFood vai re-enviar o evento se nós retornarmos false)
            return true;
        }
        Pedido p = opt.get();
        Pedido.Status statusAntigo = p.getStatus();
        p.setStatus(Pedido.Status.CANCELADO);
        pedidoRepo.save(p);
        log.info("[iFood-AUDIT] event=CAN orderId={} merchantId={} statusAntigo={} statusNovo=CANCELADO action=local_update_ok",
                orderId, r.getIfoodMerchantId(), statusAntigo);
        return true;
    }

    /**
     * Cria o pedido local já marcado como CANCELADO. Usado quando o CAN
     * chega pra um orderId que nunca passou pelo PLC (cenário comum em
     * cancelamentos rápidos da homologação).
     *
     * Diferente do tratarPedidoNovo: NÃO chama auto-confirm (seria erro
     * confirmar um pedido cancelado) e salva direto como CANCELADO.
     */
    private boolean criarPedidoLocalCancelado(Restaurante r, String orderId) {
        Map<String, Object> det = client.getOrderDetalhe(orderId);
        if (det == null || det.isEmpty()) {
            log.warn("[iFood-AUDIT] getOrderDetalhe vazio pra orderId={} — não vamos materializar", orderId);
            return false;
        }
        Pedido p = new Pedido();
        p.setRestaurante(r);
        p.setOrigem(Pedido.Origem.IFOOD);
        p.setIfoodOrderId(orderId);
        p.setIfoodDisplayId(strOf(det.get("displayId")));
        p.setStatus(Pedido.Status.CANCELADO);
        p.setTipo(extrairTipo(det));
        p.setFormaPagamento(Pedido.FormaPagamento.PIX);
        p.setModoPagamento(Pedido.ModoPagamento.ONLINE);
        p.setPago(true);

        Map<String, Object> customer = mapOf(det.get("customer"));
        p.setNomeChamada(strOf(customer.get("name")));

        Map<String, Object> delivery = mapOf(det.get("delivery"));
        p.setEnderecoEntrega(montarEndereco(mapOf(delivery.get("deliveryAddress"))));

        Map<String, Object> total = mapOf(det.get("total"));
        p.setSubtotal(decOf(total.get("subTotal")));
        p.setTaxaEntrega(decOf(total.get("deliveryFee")));
        p.setTotal(decOf(total.get("orderAmount")));

        // Itens vazios — pedido cancelado, frontend só precisa do header
        p.setItens(new ArrayList<>());
        pedidoRepo.save(p);
        log.info("[iFood-AUDIT] event=CAN orderId={} displayId={} merchantId={} action=materialized_as_CANCELADO localId={}",
                orderId, p.getIfoodDisplayId(), r.getIfoodMerchantId(), p.getId());
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
