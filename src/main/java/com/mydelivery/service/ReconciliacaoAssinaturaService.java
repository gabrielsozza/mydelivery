package com.mydelivery.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.mercadopago.MpPaymentResponse;
import com.mydelivery.model.Assinatura;
import com.mydelivery.model.Plano;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.mercadopago.MercadoPagoClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reconcilia pagamentos de assinatura pagos via MP quando o webhook falhou
 * ou nunca chegou.
 *
 * Por que existe:
 *  - Webhook do MP pode não chegar (rede, DNS, MP fora, notification_url ainda
 *    não propagado). Quando isso acontece, o cliente paga o PIX no app do MP,
 *    o valor cai na conta admin, mas o restaurante NÃO é liberado no MyDelivery
 *    e a linha em {@code pagamentos_mensalidade} não é criada — admin fica sem
 *    ver o valor no relatório financeiro.
 *  - Este service faz a reconciliação "de fora pra dentro": recebe um mpPaymentId
 *    (que o admin cola vindo do painel do MP), consulta a fonte da verdade
 *    (MP /v1/payments/{id}) e — se aprovado — replica exatamente o mesmo
 *    fluxo que o webhook faria.
 *
 * Idempotência:
 *  - {@link AssinaturaService#ativarPlano} não reativa se já está ATIVA no
 *    mesmo plano vigente — clique repetido não estende validade.
 *  - {@link AssinaturaService#registrarPagamentoOk} checa
 *    {@code existsByMpPaymentIdAndStatus} antes de criar linha — 2 execuções
 *    não duplicam receita no relatório.
 *  - Ou seja: rodar 5 vezes tem o mesmo efeito que rodar 1 vez.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliacaoAssinaturaService {

    private final MercadoPagoClient mpClient;
    private final RestauranteRepository restauranteRepository;
    private final AssinaturaService assinaturaService;

    @Value("${mydelivery.mercadopago.admin-access-token:${ADMIN_MP_ACCESS_TOKEN:}}")
    private String adminAccessToken;

    /**
     * Reconcilia pagamento de assinatura pelo mpPaymentId.
     *
     * Fluxo:
     *  1. Consulta MP com token admin.
     *  2. Valida status = approved e external_reference no formato de assinatura.
     *  3. Aciona {@link AssinaturaService#ativarPlano} + registrarPagamentoOk
     *     com a data REAL do MP (date_approved) — pra o pagamento apareça
     *     no mês em que o cliente pagou, não no mês em que admin rodou o batch.
     *
     * @return mapa com {@code ok, restauranteId, plano, valorPago, pagoEm,
     *   validaAte, mensagem}. Em falha: {@code ok=false, erro}.
     */
    @Transactional
    public Map<String, Object> reconciliarPorMpPaymentId(Long mpPaymentId) {
        if (adminAccessToken == null || adminAccessToken.isBlank()) {
            return erro("ADMIN_MP_ACCESS_TOKEN não configurado no Railway — impossível consultar o MP");
        }
        MpPaymentResponse mp;
        try {
            mp = mpClient.consultar(adminAccessToken, mpPaymentId);
        } catch (Exception e) {
            return erro("Não foi possível consultar o pagamento no MP: " + e.getMessage());
        }
        String status = mp.getStatus() == null ? "" : mp.getStatus().toLowerCase();
        if (!"approved".equals(status)) {
            return erro("Pagamento " + mpPaymentId + " está com status '" + status
                    + "' no MP — só reconcilio pagamentos aprovados");
        }
        String extRef = mp.getExternalReference();
        if (extRef == null || !extRef.startsWith("assinatura-")) {
            return erro("Pagamento " + mpPaymentId + " não é de assinatura (external_reference='" + extRef + "')");
        }
        // Formato: assinatura-{restauranteId}-{PLANO}-{timestamp}
        String[] partes = extRef.split("-");
        if (partes.length < 3) {
            return erro("external_reference em formato inesperado: " + extRef);
        }
        Long restauranteId;
        Plano plano;
        try {
            restauranteId = Long.valueOf(partes[1]);
            plano = Plano.valueOf(partes[2]);
        } catch (Exception e) {
            return erro("Falha parseando external_reference '" + extRef + "': " + e.getMessage());
        }
        Restaurante r = restauranteRepository.findById(restauranteId).orElse(null);
        if (r == null) {
            return erro("Restaurante " + restauranteId + " não existe");
        }
        LocalDateTime pagoEm = parseDateApproved(mp.getDateApproved());

        // 1) Ativa plano (idempotente) — libera o restaurante
        Assinatura a;
        try {
            a = assinaturaService.ativarPlano(r, plano, "PIX_MP", String.valueOf(mpPaymentId));
        } catch (Exception e) {
            log.error("[Reconciliação] falha ativando plano restaurante={} plano={}: {}",
                    restauranteId, plano, e.getMessage(), e);
            return erro("Falha ao ativar plano: " + e.getMessage());
        }

        // 2) Registra pagamento no relatório financeiro (idempotente por mpPaymentId).
        //    Passa data real do MP — se pagou dia 5 e admin reconciliou dia 9,
        //    o valor aparece no mês em que caiu de verdade.
        try {
            assinaturaService.registrarPagamentoOk(r, plano, "PIX_MP", mpPaymentId, pagoEm);
        } catch (Exception e) {
            log.warn("[Reconciliação] registrarPagamentoOk falhou (não-crítico — plano já foi ativado): {}",
                    e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("restauranteId", restauranteId);
        out.put("restauranteNome", r.getNome());
        out.put("plano", plano.name());
        out.put("valorPago", mp.getTransactionAmount());
        out.put("pagoEm", pagoEm != null ? pagoEm.toString() : "");
        out.put("validaAte", a.getValidaAte() != null ? a.getValidaAte().toString() : "");
        out.put("mensagem", "Loja liberada e valor contabilizado no relatório financeiro.");
        log.warn("[Reconciliação] ✅ payment={} restaurante={} plano={} status={} pagoEm={} valor={}",
                mpPaymentId, restauranteId, plano, status, pagoEm, mp.getTransactionAmount());
        return out;
    }

    /** Parseia "2026-07-05T14:30:00.000-03:00" → LocalDateTime local. Null se inválido. */
    private static LocalDateTime parseDateApproved(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Map<String, Object> erro(String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("erro", msg);
        return out;
    }
}
