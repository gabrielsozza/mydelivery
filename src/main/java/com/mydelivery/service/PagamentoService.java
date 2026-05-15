package com.mydelivery.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.mercadopago.MpPayer;
import com.mydelivery.dto.mercadopago.MpPaymentRequest;
import com.mydelivery.dto.mercadopago.MpPaymentResponse;
import com.mydelivery.dto.pagamento.PagarCartaoRequest;
import com.mydelivery.dto.pagamento.PagarPixRequest;
import com.mydelivery.model.ConfiguracaoRestaurante;
import com.mydelivery.model.Pagamento;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;
import com.mydelivery.repository.PagamentoRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.service.mercadopago.MercadoPagoClient;
import com.mydelivery.util.PixBrCodeGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PagamentoService {

    private static final String CHARS_TXID = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();

    private final PagamentoRepository pagamentoRepository;
    private final PedidoRepository pedidoRepository;
    private final ConfiguracaoRestauranteRepository configRepo;
    private final MercadoPagoClient mpClient;

    /**
     * Cria/recupera o Pagamento de um pedido. Pra PIX, gera o BR Code estático.
     * Pra cartão/applepay: placeholder — quando integrar gateway, troca este método.
     */
    @Transactional
    public Pagamento criarOuObter(Pedido pedido) {
        var existente = pagamentoRepository.findByPedidoId(pedido.getId());
        if (existente.isPresent()) return existente.get();

        Pagamento p = Pagamento.builder()
                .pedido(pedido)
                .metodo(pedido.getFormaPagamento())
                .valor(pedido.getTotal())
                .status(Pagamento.Status.PENDENTE)
                .build();

        // Se PIX online → gera BR Code agora
        if (pedido.getFormaPagamento() == Pedido.FormaPagamento.PIX
                && pedido.getModoPagamento() == Pedido.ModoPagamento.ONLINE) {
            preencherPix(p, pedido);
        }

        return pagamentoRepository.save(p);
    }

    private void preencherPix(Pagamento p, Pedido pedido) {
        Restaurante r = pedido.getRestaurante();
        var cfg = configRepo.findByRestauranteId(r.getId()).orElse(null);
        String chave = cfg != null ? cfg.getPixChave() : null;
        if (chave == null || chave.isBlank()) {
            log.warn("Restaurante {} marcou PIX online mas não cadastrou a chave PIX nas configurações", r.getId());
            // Mantém status PENDENTE mas sem BR Code — UI mostra mensagem
            return;
        }
        String txId = "MD" + gerarTxId(15) + pedido.getId(); // até 25 chars
        if (txId.length() > 25) txId = txId.substring(0, 25);
        String brCode = PixBrCodeGenerator.gerar(
                chave,
                r.getNome(),
                r.getCidade() != null ? r.getCidade() : "BRASIL",
                pedido.getTotal(),
                txId
        );
        p.setPixChave(chave);
        p.setPixTxId(txId);
        p.setPixBrCode(brCode);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  MERCADO PAGO — PIX ONLINE + CARTÃO DE CRÉDITO
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Cria cobrança PIX no Mercado Pago e devolve QR Code + copia-e-cola.
     *
     * Idempotência:
     *  - Camada 1 (banco): se já existe Pagamento PENDENTE com mpPaymentId, devolve sem chamar MP.
     *  - Camada 2 (MP): X-Idempotency-Key determinística (pedido-{id}-pix) — clique duplo do
     *    usuário ou retry de rede não geram cobrança dupla.
     */
    @Transactional
    public Pagamento criarPagamentoPixOnline(PagarPixRequest req) {
        Pedido pedido = carregarPedido(req.getPedidoId());
        log.info("[PIX] Iniciando criação para pedido #{} (restaurante {})", pedido.getId(), pedido.getRestaurante().getId());
        ConfiguracaoRestaurante cfg = exigirCredenciaisMp(pedido.getRestaurante());

        // Camada 1: já tem pagamento válido pra esse pedido?
        var existente = pagamentoRepository.findByPedidoId(pedido.getId()).orElse(null);
        if (existente != null && existente.getStatus() == Pagamento.Status.APROVADO) {
            return existente;
        }
        if (existente != null && existente.getMpPaymentId() != null
                && existente.getStatus() == Pagamento.Status.PENDENTE
                && !pixExpirou(existente)) {
            return existente; // reaproveita QR existente
        }

        String idempotencyKey = "mydelivery-pedido-" + pedido.getId() + "-pix";
        LocalDateTime expiraEm = LocalDateTime.now().plusMinutes(mpClient.props().getPixExpiracaoMin());

        // ── Monta payer pro PIX ──
        // MP exige um objeto payer NÃO-vazio. Com @JsonInclude(NON_NULL), se todos
        // os campos ficam null o objeto vira {} e o MP responde payer_cannot_be_nil.
        // Garantimos email + first_name + last_name sempre presentes.
        String email = primeiroPreenchido(
                req.getPayerEmail(),
                pedido.getCliente() != null ? pedido.getCliente().getEmail() : null);
        if (email == null || email.isBlank()) {
            // Email sintético baseado no telefone (sempre presente em pedidos online).
            // MP aceita qualquer email com formato válido — é só pra ter um identificador.
            String tel = pedido.getCliente() != null ? pedido.getCliente().getTelefone() : null;
            email = (tel != null && !tel.isBlank() ? tel.replaceAll("\\D", "") : "pedido" + pedido.getId())
                    + "@mydelivery.app";
        }

        String nomeCompleto = primeiroPreenchido(
                req.getPayerNome(),
                pedido.getCliente() != null ? pedido.getCliente().getNome() : null);
        if (nomeCompleto == null || nomeCompleto.isBlank()) nomeCompleto = "Cliente Pedido " + pedido.getId();
        String[] partesNome = nomeCompleto.trim().split("\\s+", 2);
        String firstName = partesNome[0];
        String lastName = partesNome.length > 1 ? partesNome[1] : partesNome[0];

        MpPayer payer = MpPayer.builder()
                .email(email)
                .firstName(firstName)
                .lastName(lastName)
                .build();

        String dateOfExpiration = formatarExpiracaoMp(expiraEm);
        MpPaymentRequest body = MpPaymentRequest.builder()
                .transactionAmount(pedido.getTotal())
                .paymentMethodId("pix")
                .description("MyDelivery — Pedido #" + pedido.getId())
                .externalReference("pedido-" + pedido.getId())
                .notificationUrl(mpClient.props().getWebhookUrl())
                .dateOfExpiration(dateOfExpiration)
                .payer(payer)
                .build();

        // Log sanitizado: só os primeiros chars do email + a data exata como vai pro MP.
        log.info("[PIX] Payload pro MP — pedido #{}, valor={}, payer.email={}***, firstName={}, dateOfExpiration={}",
                pedido.getId(), pedido.getTotal(),
                email.length() > 4 ? email.substring(0, 4) : email,
                firstName, dateOfExpiration);

        log.info("[PIX] Chamando MP /v1/payments — pedido #{}, valor={}, idem={}",
                pedido.getId(), pedido.getTotal(), idempotencyKey);
        MpPaymentResponse resp = mpClient.criarPagamento(cfg.getMpAccessToken(), idempotencyKey, body);
        boolean temQr = resp.getPointOfInteraction() != null
                && resp.getPointOfInteraction().getTransactionData() != null
                && resp.getPointOfInteraction().getTransactionData().getQrCode() != null;
        log.info("[PIX] MP respondeu — id={}, status={}, detail={}, temQrCode={}",
                resp.getId(), resp.getStatus(), resp.getStatusDetail(), temQr);

        Pagamento p = existente != null ? existente : Pagamento.builder()
                .pedido(pedido)
                .metodo(Pedido.FormaPagamento.PIX)
                .valor(pedido.getTotal())
                .build();

        p.setStatus(mapearStatus(resp.getStatus()));
        p.setMpPaymentId(resp.getId());
        p.setMpIdempotencyKey(idempotencyKey);
        p.setMpStatusDetail(resp.getStatusDetail());
        p.setExpiraEm(expiraEm);

        if (resp.getPointOfInteraction() != null && resp.getPointOfInteraction().getTransactionData() != null) {
            var td = resp.getPointOfInteraction().getTransactionData();
            p.setPixBrCode(td.getQrCode());
            p.setPixQrBase64(td.getQrCodeBase64());
        }

        if (p.getPixBrCode() == null || p.getPixBrCode().isBlank()) {
            log.error("[PIX] MP NÃO devolveu qr_code pra pedido #{} (status MP={}). Verifique conta/credenciais.",
                    pedido.getId(), resp.getStatus());
        }

        // Pedido permanece em AGUARDANDO_PAGAMENTO até o webhook confirmar.
        return pagamentoRepository.save(p);
    }

    /**
     * Processa pagamento com cartão de crédito (Transparent Checkout).
     *
     * O cardToken vem do MercadoPago.js v2 rodando no browser — backend nunca vê PAN/CVV.
     * MP responde sincronamente; status pode ser:
     *   - approved   → confirma na hora (webhook depois é no-op via idempotência)
     *   - in_process → fica PENDENTE, espera webhook (análise antifraude)
     *   - rejected   → devolve erro pro front exibir motivo
     */
    @Transactional
    public Pagamento processarCartao(PagarCartaoRequest req) {
        Pedido pedido = carregarPedido(req.getPedidoId());
        ConfiguracaoRestaurante cfg = exigirCredenciaisMp(pedido.getRestaurante());

        var existente = pagamentoRepository.findByPedidoId(pedido.getId()).orElse(null);
        if (existente != null && existente.getStatus() == Pagamento.Status.APROVADO) {
            return existente;
        }

        String idempotencyKey = "mydelivery-pedido-" + pedido.getId() + "-cartao";

        // Parcelas: 1x à vista quando não enviado (fluxo de delivery — sem parcelamento).
        int parcelas = req.getParcelas() != null && req.getParcelas() > 0 ? req.getParcelas() : 1;

        // E-mail: gerado a partir do telefone do cliente quando não enviado.
        // MP exige um e-mail válido em formato, mas não verifica existência real.
        String email = primeiroPreenchido(
                req.getPayerEmail(),
                pedido.getCliente() != null ? pedido.getCliente().getEmail() : null);
        if (email == null || email.isBlank()) {
            String tel = pedido.getCliente() != null ? pedido.getCliente().getTelefone() : null;
            email = (tel != null && !tel.isBlank() ? tel.replaceAll("\\D", "") : "pedido" + pedido.getId())
                    + "@mydelivery.app";
        }

        MpPaymentRequest body = MpPaymentRequest.builder()
                .transactionAmount(pedido.getTotal())
                .paymentMethodId(req.getPaymentMethodId())
                .token(req.getCardToken())
                .installments(parcelas)
                .description("MyDelivery — Pedido #" + pedido.getId())
                .externalReference("pedido-" + pedido.getId())
                .notificationUrl(mpClient.props().getWebhookUrl())
                .payer(MpPayer.builder()
                        .email(email)
                        .identification(MpPayer.Identification.builder()
                                .type(req.getPayerDocTipo())
                                .number(req.getPayerDocNumero().replaceAll("\\D", ""))
                                .build())
                        .build())
                .build();

        MpPaymentResponse resp = mpClient.criarPagamento(cfg.getMpAccessToken(), idempotencyKey, body);

        Pagamento p = existente != null ? existente : Pagamento.builder()
                .pedido(pedido)
                .metodo(Pedido.FormaPagamento.CARTAO_CREDITO)
                .valor(pedido.getTotal())
                .build();

        p.setMetodo(Pedido.FormaPagamento.CARTAO_CREDITO);
        p.setMpPaymentId(resp.getId());
        p.setMpIdempotencyKey(idempotencyKey);
        p.setMpStatusDetail(resp.getStatusDetail());
        p.setStatus(mapearStatus(resp.getStatus()));
        if (resp.getCard() != null && resp.getCard().getLastFourDigits() != null) {
            p.setCartaoFinal(resp.getCard().getLastFourDigits());
        }

        // Resposta síncrona "approved" → confirma pedido imediatamente.
        // Webhook posterior é no-op via guarda em aprovar() (camada 4).
        if ("approved".equalsIgnoreCase(resp.getStatus())) {
            p.setAprovadoEm(LocalDateTime.now());
            if (!Boolean.TRUE.equals(pedido.getPago())) {
                pedido.setPago(true);
                pedido.setPagoEm(LocalDateTime.now());
            }
            if (pedido.getStatus() == Pedido.Status.AGUARDANDO_PAGAMENTO) {
                pedido.setStatus(Pedido.Status.PENDENTE);
            }
            pedidoRepository.save(pedido);
        }

        return pagamentoRepository.save(p);
    }

    // ── Helpers ──

    private Pedido carregarPedido(Long pedidoId) {
        return pedidoRepository.findById(pedidoId)
                .orElseThrow(() -> new RuntimeException("Pedido não encontrado"));
    }

    private ConfiguracaoRestaurante exigirCredenciaisMp(Restaurante r) {
        ConfiguracaoRestaurante cfg = configRepo.findByRestauranteId(r.getId()).orElse(null);
        if (cfg == null) {
            log.error("[PIX] Restaurante {} sem ConfiguracaoRestaurante cadastrada", r.getId());
            throw new RuntimeException("Restaurante sem configuração de pagamento");
        }
        if (cfg.getMpAccessToken() == null || cfg.getMpAccessToken().isBlank()) {
            log.error("[PIX] Restaurante {} sem Access Token do Mercado Pago — configure em Configurações → Pagamento online", r.getId());
            throw new RuntimeException("Restaurante não configurou as credenciais do Mercado Pago");
        }
        return cfg;
    }

    /** Retorna o primeiro valor não-blank, ou null se todos forem null/blank. */
    private String primeiroPreenchido(String... valores) {
        if (valores == null) return null;
        for (String v : valores) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private boolean pixExpirou(Pagamento p) {
        return p.getExpiraEm() != null && p.getExpiraEm().isBefore(LocalDateTime.now());
    }

    private Pagamento.Status mapearStatus(String mpStatus) {
        if (mpStatus == null) return Pagamento.Status.PENDENTE;
        return switch (mpStatus) {
            case "approved" -> Pagamento.Status.APROVADO;
            case "rejected" -> Pagamento.Status.RECUSADO;
            case "cancelled", "refunded", "charged_back" -> Pagamento.Status.CANCELADO;
            default -> Pagamento.Status.PENDENTE; // pending, in_process, authorized
        };
    }

    /**
     * MP exige ISO-8601 com offset e EXATAMENTE 3 dígitos de milissegundo
     * (ex: 2026-05-14T15:30:00.000-03:00). O ISO_OFFSET_DATE_TIME padrão omite
     * milissegundos quando são zero e inclui nanossegundos quando presentes —
     * em ambos os casos o MP rejeita com "must be valid date and format".
     * Pattern explícito SSS força 3 dígitos sempre.
     */
    private static final DateTimeFormatter MP_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    private String formatarExpiracaoMp(LocalDateTime dt) {
        // Trunca nanos pra zerar — garante saída determinística e evita "salto" de
        // milissegundos por causa de horário com precisão maior do que MP aceita.
        return dt.withNano(0)
                .atZone(ZoneId.of("America/Sao_Paulo"))
                .toOffsetDateTime()
                .format(MP_DATE_FMT);
    }

    /**
     * Admin confirma pagamento manualmente (PIX caiu na conta etc).
     * Marca Pagamento APROVADO + Pedido pago=true + Status PENDENTE (pra fluxo normal).
     */
    @Transactional
    public Pagamento confirmar(Long pedidoId, Long restauranteId) {
        Pedido pedido = pedidoRepository.findById(pedidoId)
                .orElseThrow(() -> new RuntimeException("Pedido não encontrado"));
        if (!pedido.getRestaurante().getId().equals(restauranteId))
            throw new RuntimeException("Acesso negado");

        Pagamento p = pagamentoRepository.findByPedidoId(pedidoId)
                .orElseThrow(() -> new RuntimeException("Pagamento não encontrado pra este pedido"));

        if (p.getStatus() == Pagamento.Status.APROVADO) return p;

        p.setStatus(Pagamento.Status.APROVADO);
        p.setAprovadoEm(LocalDateTime.now());
        pagamentoRepository.save(p);

        pedido.setPago(true);
        pedido.setPagoEm(LocalDateTime.now());
        // Se estava aguardando pagamento, vira PENDENTE (entra no fluxo normal)
        if (pedido.getStatus() == Pedido.Status.AGUARDANDO_PAGAMENTO) {
            pedido.setStatus(Pedido.Status.PENDENTE);
        }
        pedidoRepository.save(pedido);

        log.info("✅ Pagamento confirmado: pedido #{} ({})", pedidoId, p.getMetodo());
        return p;
    }

    /**
     * Polling do cliente: vê se o pedido foi pago.
     * Retorna status atual do pagamento.
     */
    public Pagamento obterPorPedido(Long pedidoId) {
        return pagamentoRepository.findByPedidoId(pedidoId)
                .orElseThrow(() -> new RuntimeException("Pagamento não encontrado"));
    }

    private String gerarTxId(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(CHARS_TXID.charAt(RNG.nextInt(CHARS_TXID.length())));
        return sb.toString();
    }

    /**
     * Simulação de processamento de cartão (placeholder até integração com gateway).
     * Por enquanto: cria o pagamento com status PENDENTE e marca cartaoFinal.
     */
    @Transactional
    public Pagamento processarCartaoSimulado(Pedido pedido, String cartaoFinal4) {
        Pagamento p = criarOuObter(pedido);
        if (cartaoFinal4 != null && cartaoFinal4.length() >= 4) {
            p.setCartaoFinal(cartaoFinal4.substring(cartaoFinal4.length() - 4));
        }
        return pagamentoRepository.save(p);
    }
}
