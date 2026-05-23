package com.mydelivery.job;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.Assinatura;
import com.mydelivery.repository.AssinaturaRepository;
import com.mydelivery.service.AssinaturaPagamentoService;
import com.mydelivery.service.AssinaturaService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cobrança automática de assinaturas com cartão salvo.
 *
 * Roda 1x por dia (2h da manhã). Procura assinaturas com:
 *   - status = PENDENTE (cartão validado, aguardando fim do trial)
 *     OU status = ATIVA (renovação mensal)
 *   - proximaCobranca <= AGORA
 *   - método = CARTAO
 *   - referenciaGateway começando com "trial-card:" (customer+card MP salvo)
 *
 * Pra cada uma: chama MP via cartão salvo (sem QR, sem form, sem interação
 * do usuário). Se aprovado → ativa e estende validade. Se reprovado → marca
 * pendente_falha e mantém pra retry no dia seguinte.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CobrancaAutomaticaJob {

    private final AssinaturaRepository assinaturaRepo;
    private final AssinaturaPagamentoService pagamentoService;
    private final AssinaturaService assinaturaService;

    @Scheduled(cron = "0 0 2 * * *") // todo dia 02:00 da manhã
    @Transactional
    public void processarCobrancasPendentes() {
        LocalDateTime agora = LocalDateTime.now();
        List<Assinatura> aCobrar;
        try {
            aCobrar = assinaturaRepo.findAll().stream()
                    .filter(a -> a.getProximaCobranca() != null
                              && !a.getProximaCobranca().isAfter(agora)
                              && (a.getStatus() == Assinatura.Status.PENDENTE
                                  || a.getStatus() == Assinatura.Status.ATIVA)
                              && "CARTAO".equalsIgnoreCase(a.getMetodoPagamento())
                              && a.getReferenciaGateway() != null
                              && a.getReferenciaGateway().startsWith("trial-card:"))
                    .toList();
        } catch (Exception e) {
            log.warn("[CobrancaAuto] falha ao listar assinaturas: {}", e.getMessage());
            return;
        }

        if (aCobrar.isEmpty()) {
            log.debug("[CobrancaAuto] nada a cobrar hoje");
            return;
        }

        int ok = 0, falha = 0;
        for (Assinatura a : aCobrar) {
            try {
                var resp = pagamentoService.cobrarCartaoSalvo(
                        a.getRestaurante(), a.getPlano(), a.getReferenciaGateway());
                if (Boolean.TRUE.equals(resp.get("aprovado"))) {
                    // Ativa o plano (extende validaAte + duracaoMeses, atualiza ultimaCobranca)
                    assinaturaService.ativarPlano(a.getRestaurante(), a.getPlano(), "CARTAO",
                            resp.get("paymentId") == null ? null : String.valueOf(resp.get("paymentId")));
                    ok++;
                    log.info("[CobrancaAuto] ✅ cobrança ok — restaurante={}, plano={}, paymentId={}",
                            a.getRestaurante().getId(), a.getPlano(), resp.get("paymentId"));
                } else {
                    falha++;
                    log.warn("[CobrancaAuto] ❌ cobrança reprovada — restaurante={}, plano={}, status={}, detail={}",
                            a.getRestaurante().getId(), a.getPlano(),
                            resp.get("status"), resp.get("statusDetail"));
                    // Mantém PENDENTE pra retry no próximo tick (24h).
                    // Após N falhas, status seria mudado pra FALHA_PAGAMENTO + alerta admin — TODO V2.
                }
            } catch (Exception e) {
                falha++;
                log.error("[CobrancaAuto] erro processando assinatura {} (restaurante={}): {}",
                        a.getId(), a.getRestaurante() != null ? a.getRestaurante().getId() : null,
                        e.getMessage());
            }
        }
        log.info("[CobrancaAuto] tick concluído — total={} aprovadas={} falhas={}",
                aCobrar.size(), ok, falha);
    }
}
