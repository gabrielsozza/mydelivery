package com.mydelivery.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mydelivery.service.mercadopago.MercadoPagoClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Job de "belt & suspenders" pra reconciliação automática de assinaturas.
 *
 * Roda a cada 5min. Consulta o Mercado Pago (conta admin) por pagamentos
 * aprovados nas últimas 12 horas cujo external_reference começa com
 * "assinatura-". Pra cada um, chama {@link ReconciliacaoAssinaturaService},
 * que já é idempotente — pagamentos que o webhook já processou viram no-op.
 *
 * Cobertura: se o webhook do MP não chegar (rede, DNS quebrado, MP fora)
 * o job pega o pagamento em até 5min e libera a loja + registra no relatório.
 *
 * Kill-switch: env {@code AUTO_RECONCILIACAO_ATIVA=false} desliga sem redeploy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliacaoAssinaturaJob {

    private final MercadoPagoClient mpClient;
    private final ReconciliacaoAssinaturaService reconciliacao;

    @Value("${mydelivery.mercadopago.admin-access-token:${ADMIN_MP_ACCESS_TOKEN:}}")
    private String adminAccessToken;

    @Value("${mydelivery.auto-reconciliacao.ativo:${AUTO_RECONCILIACAO_ATIVA:true}}")
    private boolean ativo;

    @Value("${mydelivery.auto-reconciliacao.janela-horas:12}")
    private int janelaHoras;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    /**
     * Roda a cada 5min. Delay inicial de 90s pra deixar boot terminar antes.
     */
    @Scheduled(initialDelay = 90_000, fixedDelay = 5 * 60 * 1000)
    public void tick() {
        if (!ativo) return;
        if (adminAccessToken == null || adminAccessToken.isBlank()) {
            log.debug("[AutoRecon] ADMIN_MP_ACCESS_TOKEN vazio — job desligado");
            return;
        }
        try {
            OffsetDateTime agora = OffsetDateTime.now(ZoneOffset.of("-03:00"));
            String dateFrom = agora.minusHours(janelaHoras).format(ISO);
            String dateTo = agora.format(ISO);
            Map<String, Object> resp = mpClient.buscarPagamentosAprovados(adminAccessToken, dateFrom, dateTo);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) resp.getOrDefault("results", List.of());
            int total = 0, reconciliados = 0, ignorados = 0, falhas = 0;
            for (Map<String, Object> pg : results) {
                total++;
                Object extRefRaw = pg.get("external_reference");
                if (extRefRaw == null || !String.valueOf(extRefRaw).startsWith("assinatura-")) {
                    ignorados++;
                    continue;
                }
                Object idRaw = pg.get("id");
                if (idRaw == null) { ignorados++; continue; }
                Long mpPaymentId;
                try {
                    mpPaymentId = Long.valueOf(String.valueOf(idRaw));
                } catch (NumberFormatException e) {
                    ignorados++;
                    continue;
                }
                try {
                    Map<String, Object> r = reconciliacao.reconciliarPorMpPaymentId(mpPaymentId);
                    if (Boolean.TRUE.equals(r.get("ok"))) reconciliados++;
                    else falhas++;
                } catch (Exception e) {
                    falhas++;
                    log.warn("[AutoRecon] falha reconciliando payment {}: {}", mpPaymentId, e.getMessage());
                }
            }
            if (reconciliados > 0 || falhas > 0) {
                log.info("[AutoRecon] tick: janela={}h total={} reconciliados={} ignorados={} falhas={}",
                        janelaHoras, total, reconciliados, ignorados, falhas);
            } else {
                log.debug("[AutoRecon] tick: janela={}h total={} nada novo", janelaHoras, total);
            }
        } catch (Exception e) {
            log.warn("[AutoRecon] tick falhou: {}", e.getMessage());
        }
    }
}
