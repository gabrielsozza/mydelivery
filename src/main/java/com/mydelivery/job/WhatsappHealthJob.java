package com.mydelivery.job;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mydelivery.model.WhatsappHealthLog;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappHealthLogRepository;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.WhatsappHealthService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Job de saúde do WhatsApp.
 *
 * A cada 5min:
 *  - Calcula estado de cada instância (OPERACIONAL/INSTÁVEL/OFFLINE).
 *  - Persiste snapshot na whatsapp_health_log (gráfico do admin).
 *  - Se OFFLINE/INSTÁVEL e cooldown ok (15min entre tentativas) →
 *    chama Evolution.restart() automaticamente. Marca como
 *    reconexao_disparada=true no snapshot.
 *
 * A cada 24h: limpa snapshots com mais de 7 dias.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsappHealthJob {

    private final WhatsappInstanceRepository instanceRepo;
    private final WhatsappHealthLogRepository healthLogRepo;
    private final WhatsappHealthService healthService;

    /** Cooldown entre auto-reconexões da MESMA instância (anti-loop). */
    private static final int COOLDOWN_RECONEXAO_MIN = 15;

    /** Máximo de tentativas consecutivas antes de desistir do auto-restart. */
    private static final int MAX_TENTATIVAS_SEGUIDAS = 5;

    @Scheduled(fixedRate = 5 * 60_000L, initialDelay = 60_000L) // 5min, espera 1min após boot
    public void tick() {
        var todas = instanceRepo.findAll();
        log.debug("[WAHealth] avaliando {} instâncias", todas.size());

        for (WhatsappInstance inst : todas) {
            try {
                avaliarUma(inst);
            } catch (Exception e) {
                log.error("[WAHealth] erro avaliando {}: {}", inst.getInstanceName(), e.getMessage());
            }
        }
    }

    private void avaliarUma(WhatsappInstance inst) {
        WhatsappHealthLog.Estado estado = healthService.avaliarEstado(inst);
        boolean reconectou = false;

        // Auto-reconexão: só se ativa o bot, instância já foi conectada um dia,
        // está OFFLINE OU INSTÁVEL, e cooldown passou.
        boolean precisaIntervir = (estado == WhatsappHealthLog.Estado.OFFLINE
                || estado == WhatsappHealthLog.Estado.INSTAVEL);

        if (precisaIntervir
                && Boolean.TRUE.equals(inst.getBotAtivo())
                && inst.getConectadoEm() != null
                && cooldownOk(inst)
                && inst.getTentativasReconexaoSeguidas() < MAX_TENTATIVAS_SEGUIDAS) {

            log.warn("[WAHealth] instância {} em {} — disparando auto-reconexão",
                    inst.getInstanceName(), estado);
            reconectou = healthService.tentarReconectar(inst);
        }

        healthService.registrarSnapshot(inst, reconectou);
    }

    private boolean cooldownOk(WhatsappInstance inst) {
        if (inst.getUltimaTentativaReconexaoEm() == null) return true;
        long min = Duration.between(inst.getUltimaTentativaReconexaoEm(), LocalDateTime.now()).toMinutes();
        return min >= COOLDOWN_RECONEXAO_MIN;
    }

    /** Limpa snapshots antigos (mantém 7 dias). Roda diariamente às 4h. */
    @Scheduled(cron = "0 0 4 * * *")
    public void limpar() {
        LocalDateTime corte = LocalDateTime.now().minusDays(7);
        try {
            healthLogRepo.deleteByEmBefore(corte);
            log.info("[WAHealth] snapshots anteriores a {} removidos", corte);
        } catch (Exception e) {
            log.error("[WAHealth] erro na limpeza: {}", e.getMessage());
        }
    }
}
