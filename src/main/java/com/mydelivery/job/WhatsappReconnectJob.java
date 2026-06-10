package com.mydelivery.job;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.EvolutionClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Restart preventivo das sessões WhatsApp a cada 18h.
 *
 * MOTIVAÇÃO REAL: WhatsApp aplica shadow-ban silencioso depois de algumas horas
 * em instâncias persistentes — Evolution mostra "open" mas o WhatsApp para de
 * entregar mensagens (webhook MESSAGES_UPSERT nunca dispara). Refrescar a sessão
 * via /instance/restart força o Baileys a re-estabelecer a conexão, o que
 * frequentemente "destrava" a entrega de mensagens.
 *
 * Não exige re-pareamento (não pede QR novo). Mantém o número logado.
 *
 * Roda a cada 18h fixo — equilíbrio entre prevenção e overhead. Pode reduzir
 * pra 6h se o problema voltar.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsappReconnectJob {

    private final WhatsappInstanceRepository repo;
    private final EvolutionClient evolutionClient;

    /**
     * fixedDelay = 6h (reduzido de 18h). Restaurantes reportavam que o bot
     * "dormia" em silêncio depois de algumas horas (Baileys da Evolution
     * acumula problemas de fila). 6h é mais agressivo mas pesa muito pouco
     * — operação é só um restart de WebSocket por instância (~200ms cada).
     *
     * initialDelay = 5min depois do boot pra não bater na Evolution antes
     * do app estar 100% inicializado.
     */
    @Scheduled(fixedDelay = 6L * 60 * 60 * 1000, initialDelay = 5L * 60 * 1000)
    public void restartPreventivo() {
        List<WhatsappInstance> ativas;
        try {
            ativas = repo.findAll().stream()
                    .filter(i -> i.getStatus() == WhatsappInstance.Status.CONECTADA
                              && Boolean.TRUE.equals(i.getBotAtivo()))
                    .toList();
        } catch (Exception e) {
            log.warn("[WhatsappReconnectJob] falha ao listar instâncias: {}", e.getMessage());
            return;
        }
        if (ativas.isEmpty()) {
            log.debug("[WhatsappReconnectJob] sem instâncias ativas — nada a fazer");
            return;
        }

        int ok = 0, falha = 0;
        LocalDateTime inicio = LocalDateTime.now();
        for (WhatsappInstance inst : ativas) {
            try {
                evolutionClient.restart(inst.getInstanceName());
                ok++;
                log.info("[WhatsappReconnectJob] restart ok — {} (restaurante={})",
                        inst.getInstanceName(),
                        inst.getRestaurante() != null ? inst.getRestaurante().getId() : null);
            } catch (Exception e) {
                falha++;
                log.warn("[WhatsappReconnectJob] restart falhou pra {}: {}",
                        inst.getInstanceName(), e.getMessage());
            }
        }
        log.info("[WhatsappReconnectJob] tick concluído — total={} ok={} falha={} duração={}s",
                ativas.size(), ok, falha,
                java.time.Duration.between(inicio, LocalDateTime.now()).getSeconds());
    }
}
