package com.mydelivery.job;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.Pedido;
import com.mydelivery.repository.PedidoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-fecha pedidos esquecidos em SAIU_ENTREGA.
 *
 * Contexto: muitos restaurantes esquecem de marcar "Entregue" depois que
 * o pedido sai pra entrega. Pedido fica acumulando no painel sem refletir
 * a operação real. Solução: depois de {@value #MINUTOS_LIMITE} minutos sem
 * atualização (UPDATE timestamp do JPA), o sistema assume que foi
 * entregue e move pra ENTREGUE.
 *
 * Regras:
 *  - Só DELIVERY/RETIRADA. Mesa/balcão têm fluxo próprio (fechamento de conta).
 *  - Só SAIU_ENTREGA. Não promovemos EM_PREPARO ou CONFIRMADO porque podem
 *    estar legitimamente esquecidos por cancelamento ou pausa.
 *  - Usa atualizadoEm (UpdateTimestamp do JPA) — qualquer interação do
 *    restaurante com o pedido reseta o timer, então é seguro.
 *  - Roda a cada 15min — granularidade boa o suficiente.
 *
 * Auditoria: log INFO por pedido fechado pra ficar rastreável.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoEntregueJob {

    /** Após 2h30 sem atualização, pedido SAIU_ENTREGA vira ENTREGUE. */
    private static final int MINUTOS_LIMITE = 150;

    private final PedidoRepository pedidoRepo;
    private final com.mydelivery.service.ifood.IfoodClient ifoodClient;

    @Scheduled(fixedRate = 15 * 60_000L, initialDelay = 5 * 60_000L)
    @Transactional
    public void rodar() {
        LocalDateTime limite = LocalDateTime.now().minusMinutes(MINUTOS_LIMITE);
        List<Pedido> esquecidos = pedidoRepo.findEsquecidosParaEntregaAutomatica(limite);
        if (esquecidos.isEmpty()) {
            log.debug("[AutoEntregue] nenhum pedido esquecido");
            return;
        }
        int fechados = 0;
        for (Pedido p : esquecidos) {
            try {
                p.setStatus(Pedido.Status.ENTREGUE);
                pedidoRepo.save(p);
                // Propaga pra iFood quando aplicável — sem isso o pedido fica
                // marcado como ENTREGUE localmente mas a Order API do iFood
                // continua aguardando CONCLUDED (e o restaurante "perde"
                // pontos na homologação/operação real).
                if (p.getOrigem() == Pedido.Origem.IFOOD
                        && p.getIfoodOrderId() != null
                        && !p.getIfoodOrderId().isBlank()) {
                    try {
                        ifoodClient.entregue(p.getIfoodOrderId());
                    } catch (Exception ife) {
                        log.warn("[AutoEntregue] iFood delivered falhou pra orderId={}: {}",
                                p.getIfoodOrderId(), ife.getMessage());
                    }
                }
                fechados++;
                log.info("[AutoEntregue] pedido#{} (rest={}) marcado ENTREGUE automaticamente — " +
                        "ficou {}min sem atualização",
                        p.getId(),
                        p.getRestaurante() != null ? p.getRestaurante().getId() : null,
                        java.time.Duration.between(p.getAtualizadoEm(), LocalDateTime.now()).toMinutes());
            } catch (Exception e) {
                log.error("[AutoEntregue] falha ao fechar pedido#{}: {}", p.getId(), e.getMessage());
            }
        }
        log.info("[AutoEntregue] tick — {} pedidos auto-fechados", fechados);
    }
}
