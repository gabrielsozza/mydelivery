package com.mydelivery.job;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mydelivery.model.WhatsappAcaoAutomatica;
import com.mydelivery.model.WhatsappHealthLog;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappAcaoAutomaticaRepository;
import com.mydelivery.repository.WhatsappHealthLogRepository;
import com.mydelivery.repository.WhatsappIncidenteRepository;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.WhatsappHealthService;
import com.mydelivery.service.whatsapp.WhatsappIncidenteService;

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
    private final WhatsappIncidenteService incidenteService;
    private final WhatsappIncidenteRepository incidenteRepo;
    private final WhatsappAcaoAutomaticaRepository acaoRepo;

    /** Cooldown entre auto-reconexões da MESMA instância (anti-loop). */
    private static final int COOLDOWN_RECONEXAO_MIN = 15;

    /** Máximo de tentativas consecutivas antes de desistir do auto-restart.
     *  Reduzido de 5 → 3: shadow ban persistente não resolve com restart,
     *  insistir é só esforço inútil. Após 3 falhas, abre INSTANCIA_INSTAVEL
     *  e respeita cooldown longo (6h) antes de tentar de novo. */
    private static final int MAX_TENTATIVAS_SEGUIDAS = 3;

    /** Após esgotar tentativas, espera N horas antes de tentar restart de
     *  novo. Shadow ban tem que "esfriar" — restart imediato re-flagga. */
    private static final int COOLDOWN_POS_ESGOTADO_HORAS = 6;

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

        // ── Detectores rodam ANTES da decisão de intervir, pra classificar
        //    a causa raiz. Cada um abre/resolve incidente do seu tipo.
        try { incidenteService.detectarEvolutionFora(inst); } catch (Exception e) { logDet(e); }
        try { incidenteService.detectarBaileysTravado(inst); } catch (Exception e) { logDet(e); }
        try { incidenteService.detectarShadowBan(inst); } catch (Exception e) { logDet(e); }
        try { incidenteService.detectarRecuperacaoEsgotada(inst); } catch (Exception e) { logDet(e); }

        // Auto-reconexão: só se ativa o bot, instância já foi conectada um dia,
        // está OFFLINE OU INSTÁVEL, e cooldown passou.
        //
        // CRITICAL: NÃO disparar reconexão se o dono desconectou manualmente
        // (desconectadoManualmente=true) — esse é fluxo normal, ficaria
        // reconectando contra a vontade do usuário.
        // Também não dispara em AGUARDANDO_CONEXAO (instância nova esperando QR).
        boolean precisaIntervir = (estado == WhatsappHealthLog.Estado.OFFLINE
                || estado == WhatsappHealthLog.Estado.INSTAVEL);
        boolean foiManual = Boolean.TRUE.equals(inst.getDesconectadoManualmente());

        // Se já esgotou tentativas, espera cooldown LONGO (6h) antes de
        // tentar de novo. Loop infinito de restart re-flagga shadow ban
        // — precisa dar tempo do WhatsApp "esfriar" o número.
        boolean esgotouEPrecisaResfriar = false;
        if (inst.getTentativasReconexaoSeguidas() != null
                && inst.getTentativasReconexaoSeguidas() >= MAX_TENTATIVAS_SEGUIDAS) {
            if (inst.getUltimaTentativaReconexaoEm() == null
                    || Duration.between(inst.getUltimaTentativaReconexaoEm(), LocalDateTime.now()).toHours()
                            < COOLDOWN_POS_ESGOTADO_HORAS) {
                esgotouEPrecisaResfriar = true;
            } else {
                // Cooldown longo passou — zera contador e dá nova chance
                log.info("[WAHealth] cooldown pós-esgotado ({}h) terminou pra {} — resetando contador pra nova tentativa",
                        COOLDOWN_POS_ESGOTADO_HORAS, inst.getInstanceName());
                inst.setTentativasReconexaoSeguidas(0);
                instanceRepo.save(inst);
            }
        }

        if (precisaIntervir
                && !foiManual
                && !esgotouEPrecisaResfriar
                && Boolean.TRUE.equals(inst.getBotAtivo())
                && inst.getConectadoEm() != null
                && cooldownOk(inst)
                && inst.getTentativasReconexaoSeguidas() < MAX_TENTATIVAS_SEGUIDAS) {

            log.warn("[WAHealth] instância {} em {} — disparando auto-reconexão",
                    inst.getInstanceName(), estado);
            reconectou = healthService.tentarReconectar(inst);

            // Registra a tentativa pra histórico auditável. Vincula ao incidente
            // mais relevante (BAILEYS_TRAVADO ou EVOLUTION_FORA) se houver.
            var incidenteRelacionado = incidenteRepo
                    .findFirstByInstanceIdAndTipoAndResolvidoEmIsNull(
                            inst.getId(),
                            com.mydelivery.model.WhatsappIncidente.Tipo.BAILEYS_TRAVADO)
                    .orElse(incidenteRepo
                            .findFirstByInstanceIdAndTipoAndResolvidoEmIsNull(
                                    inst.getId(),
                                    com.mydelivery.model.WhatsappIncidente.Tipo.EVOLUTION_FORA)
                            .orElse(null));
            incidenteService.registrarAcao(incidenteRelacionado, inst,
                    WhatsappAcaoAutomatica.Acao.RECONECTAR,
                    reconectou ? WhatsappAcaoAutomatica.Resultado.OK
                              : WhatsappAcaoAutomatica.Resultado.FALHA,
                    "tentativa #" + inst.getTentativasReconexaoSeguidas());
        }

        healthService.registrarSnapshot(inst, reconectou);
    }

    private void logDet(Exception e) {
        log.debug("[WAHealth] detector falhou (silenciado): {}", e.getMessage());
    }

    private boolean cooldownOk(WhatsappInstance inst) {
        if (inst.getUltimaTentativaReconexaoEm() == null) return true;
        long min = Duration.between(inst.getUltimaTentativaReconexaoEm(), LocalDateTime.now()).toMinutes();
        return min >= COOLDOWN_RECONEXAO_MIN;
    }

    /** Limpa snapshots antigos (7 dias) + incidentes resolvidos antigos (30 dias)
     *  + ações antigas (30 dias). Roda diariamente às 4h. */
    @Scheduled(cron = "0 0 4 * * *")
    public void limpar() {
        LocalDateTime corte7 = LocalDateTime.now().minusDays(7);
        LocalDateTime corte30 = LocalDateTime.now().minusDays(30);
        try {
            healthLogRepo.deleteByEmBefore(corte7);
            // Só apaga incidentes JÁ resolvidos há mais de 30 dias — abertos
            // ficam pra sempre até serem fechados manualmente ou automaticamente.
            incidenteRepo.deleteByAbertoEmBefore(corte30);
            acaoRepo.deleteByEmBefore(corte30);
            log.info("[WAHealth] limpeza diária ok: snapshots>7d, incidentes>30d, ações>30d");
        } catch (Exception e) {
            log.error("[WAHealth] erro na limpeza: {}", e.getMessage());
        }
    }
}
