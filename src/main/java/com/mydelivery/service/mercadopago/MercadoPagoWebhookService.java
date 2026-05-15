package com.mydelivery.service.mercadopago;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.mercadopago.MpPaymentResponse;
import com.mydelivery.model.ConfiguracaoRestaurante;
import com.mydelivery.model.MercadoPagoEventoProcessado;
import com.mydelivery.model.Pagamento;
import com.mydelivery.model.Pedido;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;
import com.mydelivery.repository.MercadoPagoEventoProcessadoRepository;
import com.mydelivery.repository.PagamentoRepository;
import com.mydelivery.repository.PedidoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Processa webhooks do Mercado Pago.
 *
 * Fluxo:
 *  1. Idempotência por eventId (x-request-id). Já processado → no-op.
 *  2. Resolve tenant: acha Pagamento local pelo mpPaymentId → pega restaurante → pega accessToken.
 *  3. Valida assinatura HMAC com o webhook secret do tenant.
 *  4. Consulta /v1/payments/{id} no MP (única fonte da verdade — payload do webhook só notifica).
 *  5. Aplica transição de status no Pagamento e no Pedido.
 *  6. Grava eventId na MESMA transação — atomicidade impede reprocessamento parcial.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MercadoPagoWebhookService {

    private final PagamentoRepository pagamentoRepository;
    private final PedidoRepository pedidoRepository;
    private final ConfiguracaoRestauranteRepository configRepo;
    private final MercadoPagoEventoProcessadoRepository eventoRepo;
    private final MercadoPagoSignatureValidator signatureValidator;
    private final MercadoPagoClient mpClient;

    /**
     * @return Resultado.OK → respondemos 200. INVALIDO → 401. ERRO → 500 (MP reentrega).
     */
    @Transactional
    public Resultado processar(WebhookInput input) {
        // 1. Idempotência: já processei esse eventId?
        if (input.eventId() != null && eventoRepo.existsById(input.eventId())) {
            log.debug("Webhook MP duplicado ignorado: eventId={}", input.eventId());
            return Resultado.OK;
        }

        // Eventos que não são de pagamento — só ack pra não receber retry
        if (!"payment".equalsIgnoreCase(input.tipo())) {
            log.debug("Webhook MP ignorado (tipo={}): {}", input.tipo(), input.dataId());
            registrarEvento(input, null);
            return Resultado.OK;
        }

        Long mpPaymentId;
        try {
            mpPaymentId = Long.valueOf(input.dataId());
        } catch (NumberFormatException e) {
            log.warn("data.id inválido no webhook MP: {}", input.dataId());
            return Resultado.INVALIDO;
        }

        // 2. Resolve tenant pelo Pagamento local
        Pagamento pagamento = pagamentoRepository.findByMpPaymentId(mpPaymentId).orElse(null);
        if (pagamento == null) {
            // Pode ser webhook chegando antes do POST /v1/payments retornar — MP reentrega.
            // Devolvemos 404 sutil (500 forçaria retry; 200 perderia o evento).
            // Optamos por 200 + log: na prática o front faz polling, então mesmo perdendo
            // um webhook a aprovação eventualmente acontece via consulta ativa.
            log.warn("Webhook MP recebido pra payment {} sem Pagamento local correspondente — ignorando", mpPaymentId);
            return Resultado.OK;
        }

        Pedido pedido = pagamento.getPedido();
        ConfiguracaoRestaurante cfg = configRepo.findByRestauranteId(pedido.getRestaurante().getId())
                .orElse(null);
        if (cfg == null || cfg.getMpAccessToken() == null || cfg.getMpAccessToken().isBlank()) {
            log.error("Webhook MP pra pedido {} mas restaurante {} sem credenciais MP",
                    pedido.getId(), pedido.getRestaurante().getId());
            return Resultado.ERRO;
        }

        // 3. Valida assinatura
        boolean assinaturaOk = signatureValidator.valido(
                input.signatureHeader(),
                input.eventId(),
                input.dataId(),
                cfg.getMpWebhookSecret());
        if (!assinaturaOk) {
            log.warn("Assinatura inválida no webhook MP pra payment {}", mpPaymentId);
            return Resultado.INVALIDO;
        }

        // 4. Consulta real no MP (payload do webhook é só notificação)
        MpPaymentResponse atual = mpClient.consultar(cfg.getMpAccessToken(), mpPaymentId);

        // 5. Aplica transição
        aplicarStatus(pagamento, pedido, atual);

        // 6. Grava idempotência (mesma transação)
        registrarEvento(input, mpPaymentId);

        return Resultado.OK;
    }

    private void aplicarStatus(Pagamento pagamento, Pedido pedido, MpPaymentResponse atual) {
        String novo = atual.getStatus();
        pagamento.setMpStatusDetail(atual.getStatusDetail());

        switch (novo == null ? "" : novo) {
            case "approved" -> aprovar(pagamento, pedido);
            case "rejected" -> {
                if (pagamento.getStatus() != Pagamento.Status.APROVADO) {
                    pagamento.setStatus(Pagamento.Status.RECUSADO);
                }
            }
            case "cancelled" -> {
                if (pagamento.getStatus() != Pagamento.Status.APROVADO) {
                    pagamento.setStatus(Pagamento.Status.CANCELADO);
                }
            }
            case "refunded", "charged_back" -> {
                // Estorno: pagamento volta a CANCELADO, pedido fica como está
                // (admin trata manualmente — auto-cancelar pode quebrar logística).
                pagamento.setStatus(Pagamento.Status.CANCELADO);
                log.warn("Pagamento {} estornado (status={}). Pedido {} requer revisão manual.",
                        pagamento.getId(), novo, pedido.getId());
            }
            default -> log.debug("Status MP '{}' não dispara transição local pra pagamento {}", novo, pagamento.getId());
        }

        pagamentoRepository.save(pagamento);
        pedidoRepository.save(pedido);
    }

    /**
     * Aprovação — guarda explícita contra dupla aplicação (camada 4 de idempotência).
     * Se já está APROVADO, no-op.
     */
    private void aprovar(Pagamento pagamento, Pedido pedido) {
        if (pagamento.getStatus() == Pagamento.Status.APROVADO) return;

        pagamento.setStatus(Pagamento.Status.APROVADO);
        pagamento.setAprovadoEm(LocalDateTime.now());

        if (!Boolean.TRUE.equals(pedido.getPago())) {
            pedido.setPago(true);
            pedido.setPagoEm(LocalDateTime.now());
        }
        if (pedido.getStatus() == Pedido.Status.AGUARDANDO_PAGAMENTO) {
            pedido.setStatus(Pedido.Status.PENDENTE);
        }

        log.info("✅ Webhook MP confirmou pagamento: pedido #{} (mpPaymentId={})",
                pedido.getId(), pagamento.getMpPaymentId());
    }

    private void registrarEvento(WebhookInput input, Long paymentId) {
        if (input.eventId() == null) return;
        eventoRepo.save(MercadoPagoEventoProcessado.builder()
                .eventId(input.eventId())
                .paymentId(paymentId)
                .tipo(input.tipo())
                .acao(input.acao())
                .build());
    }

    /** Input normalizado do webhook — parsing fica no controller. */
    public record WebhookInput(
            String eventId,       // x-request-id
            String signatureHeader, // x-signature
            String tipo,          // "payment" etc.
            String acao,          // "payment.created", "payment.updated"
            String dataId         // ID do recurso (payment.id pra type=payment)
    ) {}

    public enum Resultado { OK, INVALIDO, ERRO }
}
