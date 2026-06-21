package com.mydelivery.service.mercadopago;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.mercadopago.MpPaymentResponse;
import com.mydelivery.model.Assinatura;
import com.mydelivery.model.ConfiguracaoRestaurante;
import com.mydelivery.model.MercadoPagoEventoProcessado;
import com.mydelivery.model.Pagamento;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.Plano;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;
import com.mydelivery.repository.MercadoPagoEventoProcessadoRepository;
import com.mydelivery.repository.PagamentoRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.AssinaturaService;

import org.springframework.beans.factory.annotation.Value;
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
public class MercadoPagoWebhookService {

    private final PagamentoRepository pagamentoRepository;
    private final PedidoRepository pedidoRepository;
    private final ConfiguracaoRestauranteRepository configRepo;
    private final MercadoPagoEventoProcessadoRepository eventoRepo;
    private final MercadoPagoSignatureValidator signatureValidator;
    private final MercadoPagoClient mpClient;
    private final RestauranteRepository restauranteRepository;
    private final AssinaturaService assinaturaService;
    private final String adminAccessToken;

    public MercadoPagoWebhookService(
            PagamentoRepository pagamentoRepository,
            PedidoRepository pedidoRepository,
            ConfiguracaoRestauranteRepository configRepo,
            MercadoPagoEventoProcessadoRepository eventoRepo,
            MercadoPagoSignatureValidator signatureValidator,
            MercadoPagoClient mpClient,
            RestauranteRepository restauranteRepository,
            AssinaturaService assinaturaService,
            @Value("${mydelivery.mercadopago.admin-access-token:${ADMIN_MP_ACCESS_TOKEN:}}") String adminAccessToken) {
        this.pagamentoRepository = pagamentoRepository;
        this.pedidoRepository = pedidoRepository;
        this.configRepo = configRepo;
        this.eventoRepo = eventoRepo;
        this.signatureValidator = signatureValidator;
        this.mpClient = mpClient;
        this.restauranteRepository = restauranteRepository;
        this.assinaturaService = assinaturaService;
        this.adminAccessToken = adminAccessToken;
    }

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
            // Não encontrou pagamento de PEDIDO. Pode ser:
            //   (a) Webhook chegou antes do POST /v1/payments retornar → MP reentrega
            //   (b) Pagamento de ASSINATURA (PIX/Checkout Pro) — não passa por PagamentoRepository
            //
            // Pra (b): consulta MP com token ADMIN pra ler external_reference.
            // Se começar com "assinatura-", processa como ativação de assinatura.
            Resultado r = tentarProcessarComoAssinatura(mpPaymentId, input);
            if (r != null) return r;
            log.warn("Webhook MP recebido pra payment {} sem Pagamento local nem assinatura correspondente — ignorando", mpPaymentId);
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

    /**
     * Tenta interpretar o webhook como pagamento de ASSINATURA.
     *
     * Diferente de pedido, pagamento de assinatura NÃO passa por PagamentoRepository.
     * Identificamos pelo external_reference, formato: "assinatura-{restauranteId}-{PLANO}-{timestamp}".
     *
     * Se identificar e o pagamento estiver APPROVED, ativa o plano via AssinaturaService.
     * Retorna {@code null} quando não for assinatura — caller continua o fluxo padrão.
     */
    private Resultado tentarProcessarComoAssinatura(Long mpPaymentId, WebhookInput input) {
        if (adminAccessToken == null || adminAccessToken.isBlank()) {
            log.debug("[Webhook:Assinatura] ADMIN_MP_ACCESS_TOKEN não configurado — pulando fallback");
            return null;
        }
        MpPaymentResponse atual;
        try {
            atual = mpClient.consultar(adminAccessToken, mpPaymentId);
        } catch (Exception e) {
            log.warn("[Webhook:Assinatura] consulta MP com token admin falhou pra payment {}: {}",
                    mpPaymentId, e.getMessage());
            return null;
        }
        String extRef = atual.getExternalReference();
        if (extRef == null || !extRef.startsWith("assinatura-")) {
            return null; // não é assinatura — caller segue fluxo padrão
        }

        // Parse: assinatura-{restauranteId}-{PLANO}-{timestamp}
        String[] partes = extRef.split("-");
        if (partes.length < 3) {
            log.warn("[Webhook:Assinatura] external_reference em formato inesperado: {}", extRef);
            registrarEvento(input, mpPaymentId);
            return Resultado.OK;
        }
        Long restauranteId;
        Plano plano;
        try {
            restauranteId = Long.valueOf(partes[1]);
            plano = Plano.valueOf(partes[2]);
        } catch (Exception e) {
            log.warn("[Webhook:Assinatura] falha parseando external_reference '{}': {}", extRef, e.getMessage());
            registrarEvento(input, mpPaymentId);
            return Resultado.OK;
        }

        Restaurante r = restauranteRepository.findById(restauranteId).orElse(null);
        if (r == null) {
            log.warn("[Webhook:Assinatura] restaurante {} não encontrado (extRef={})", restauranteId, extRef);
            registrarEvento(input, mpPaymentId);
            return Resultado.OK;
        }

        String status = atual.getStatus() != null ? atual.getStatus().toLowerCase() : "";
        log.info("[Webhook:Assinatura] payment {} restaurante={} plano={} status={}",
                mpPaymentId, restauranteId, plano, status);

        if ("approved".equals(status)) {
            try {
                Assinatura a = assinaturaService.ativarPlano(r, plano, "PIX_MP",
                        String.valueOf(mpPaymentId));
                try {
                    assinaturaService.registrarPagamentoOk(r, plano, "PIX_MP", mpPaymentId);
                } catch (Exception e) {
                    log.warn("[Webhook:Assinatura] registrarPagamentoOk falhou (não-crítico): {}", e.getMessage());
                }
                log.info("✅ [Webhook:Assinatura] plano ativado — restaurante={}, plano={}, validaAte={}",
                        restauranteId, plano, a.getValidaAte());
            } catch (Exception e) {
                log.error("[Webhook:Assinatura] FALHA ao ativar plano restaurante={}, plano={}: {}",
                        restauranteId, plano, e.getMessage(), e);
                return Resultado.ERRO; // MP reentrega
            }
        } else if ("rejected".equals(status) || "cancelled".equals(status)) {
            log.info("[Webhook:Assinatura] pagamento {} status={} — sem ativação", mpPaymentId, status);
            // Status final negativo — não ativa, mas registra evento pra não reprocessar
        } else {
            // pending, in_process, etc — aguarda próximo webhook
            log.info("[Webhook:Assinatura] payment {} ainda pendente (status={}) — aguardando", mpPaymentId, status);
        }

        registrarEvento(input, mpPaymentId);
        return Resultado.OK;
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
