package com.mydelivery.job;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Cooldown entre auto-reconexões da MESMA instância (anti-loop).
     * OVERHAUL Jul/2026: 15min era TÃO curto que o WhatsApp interpretava
     * reconexões repetidas como comportamento de bot mal-intencionado.
     * Cada reconexão renova fingerprint da sessão Baileys — o WA marca isso.
     * 60min dá tempo do algoritmo deles "esfriar" a leitura antes da próxima
     * tentativa. Se problema é persistente, é sinal de que restart não resolve
     * — precisa intervenção humana (mudar proxy, mudar número, warmup).
     */
    private static final int COOLDOWN_RECONEXAO_MIN = 60;

    /** Máximo de tentativas consecutivas antes de desistir do auto-restart.
     *  OVERHAUL Jul/2026: reduzido de 3 → 2. Insistir mais de 2x em 12h é loop
     *  inútil que só piora shadow ban. Após 2 falhas, abre INSTANCIA_INSTAVEL
     *  e respeita cooldown MUITO longo antes de tentar de novo. */
    private static final int MAX_TENTATIVAS_SEGUIDAS = 2;

    /** Após esgotar tentativas, espera N horas antes de tentar restart de
     *  novo. OVERHAUL Jul/2026: 6h → 12h. Shadow ban de verdade só esfria com
     *  o número parado. 12h alinha com "espera até amanhã" — restaurante fecha
     *  de madrugada, na manhã seguinte o WA já limpou o histórico suspeito. */
    private static final int COOLDOWN_POS_ESGOTADO_HORAS = 12;

    /** Janela mínima entre QUALQUER auto-reconexão no sistema inteiro.
     *  Antes: 5 instâncias podiam reconectar simultaneamente no mesmo tick,
     *  estouravam /instance/restart em paralelo no Evolution, ele engasgava
     *  e derrubava todas em cascata (exatamente o padrão visto às 22:16 com
     *  5 lojas em BAILEYS_TRAVADO no MESMO timestamp). Com 90s de spacing
     *  global, no MÁXIMO 1 restart por tick — as outras esperam o próximo
     *  tick (5min). Pior caso: instância em problema espera ~5min em vez
     *  de derrubar o sistema inteiro. */
    private static final int THROTTLE_GLOBAL_SEGUNDOS = 90;

    /** Timestamp da última auto-reconexão disparada por este job em qualquer
     *  instância. Static = compartilhado entre threads. AtomicReference
     *  garante leitura/escrita coerente sem lock. */
    private static final AtomicReference<LocalDateTime> ULTIMA_RECONEXAO_GLOBAL =
            new AtomicReference<>(null);

    /** Cooldown longo aplicado quando a causa raiz é SHADOW_BAN_SUSPEITO.
     *  OVERHAUL Jul/2026: 4h → 8h. Empiricamente 4h não bastava — números que
     *  voltavam nesse tempo eram re-banidos em minutos. 8h coincide com "dormir
     *  a noite" — se ban veio na hora do jantar, só volta ao acordar. */
    private static final int COOLDOWN_SHADOW_BAN_HORAS = 8;

    /**
     * NOVO Jul/2026: cooldown mínimo aplicado a QUALQUER instância nas primeiras
     * 48h após conectar. Conta nova é ultra-sensível a reconexão — o WA usa isso
     * como sinal principal de bot. Nesse período, NÃO fazemos auto-reconexão
     * nenhuma (nem tentativa 1). Se cair, o dono precisa reconectar manualmente.
     */
    private static final int WARMUP_HORAS_CONTA_NOVA = 48;

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

        // GUARD 1 — SHADOW BAN: se a causa raiz é número silenciado pelo WA,
        // restart só piora (Baileys re-conecta, WA re-flagga, vira loop).
        // Espera 4h antes de tentar — janela do algoritmo deles esfriar.
        boolean temShadowBanAberto = false;
        try {
            temShadowBanAberto = incidenteRepo
                    .findFirstByInstanceIdAndTipoAndResolvidoEmIsNull(
                            inst.getId(),
                            com.mydelivery.model.WhatsappIncidente.Tipo.SHADOW_BAN_SUSPEITO)
                    .isPresent();
        } catch (Exception e) {
            log.debug("[WAHealth] check shadow ban falhou: {}", e.getMessage());
        }
        boolean emCooldownShadowBan = false;
        if (temShadowBanAberto) {
            LocalDateTime ult = inst.getUltimaTentativaReconexaoEm();
            if (ult == null || Duration.between(ult, LocalDateTime.now()).toHours() < COOLDOWN_SHADOW_BAN_HORAS) {
                emCooldownShadowBan = true;
                log.info("[WAHealth] {} em SHADOW_BAN — pulando auto-reconexão ({}h cooldown). Restart só re-flagga.",
                        inst.getInstanceName(), COOLDOWN_SHADOW_BAN_HORAS);
            }
        }

        // GUARD 2 — THROTTLE GLOBAL: no máximo 1 auto-reconexão a cada 90s
        // no sistema inteiro. Evita cascata onde 5 instâncias estouram
        // /instance/restart simultaneamente no Evolution e derrubam tudo.
        boolean throttleGlobalBloqueado = false;
        LocalDateTime ultGlobal = ULTIMA_RECONEXAO_GLOBAL.get();
        if (ultGlobal != null
                && Duration.between(ultGlobal, LocalDateTime.now()).getSeconds() < THROTTLE_GLOBAL_SEGUNDOS) {
            throttleGlobalBloqueado = true;
        }

        // GUARD 3 — WARMUP DE CONTA NOVA (Jul/2026): nas primeiras 48h após
        // conectar, ZERO auto-reconexão. Conta nova é ultra-sensível — WA usa
        // reconexões nesse período como principal sinal de bot. Se cair, o
        // dono reconecta manualmente. Sem exceção — nem em falha real, restart
        // automático em conta nova é o motivo #1 de shadow ban logo no início.
        boolean emWarmup = false;
        if (inst.getConectadoEm() != null
                && Duration.between(inst.getConectadoEm(), LocalDateTime.now()).toHours() < WARMUP_HORAS_CONTA_NOVA) {
            emWarmup = true;
        }

        // GUARD 4 — HORÁRIO DE PICO (Jul/2026): NÃO faz auto-reconexão em pico
        // de operação do restaurante (11:30-14h almoço, 18h-23h jantar). Se
        // cair no meio do jantar, restart mata pedidos em andamento e ainda
        // gera flag pro WA. Espera até o próximo tick (pode passar até 30min
        // sem reconectar em pico, mas evita quebrar a operação). Fora do pico,
        // reconexão rola normal.
        boolean emPicoOperacao = false;
        int horaAgora = LocalDateTime.now().getHour();
        if ((horaAgora >= 11 && horaAgora <= 13) || (horaAgora >= 18 && horaAgora <= 22)) {
            emPicoOperacao = true;
        }

        if (precisaIntervir
                && !foiManual
                && !esgotouEPrecisaResfriar
                && !emCooldownShadowBan
                && !throttleGlobalBloqueado
                && !emWarmup
                && !emPicoOperacao
                && Boolean.TRUE.equals(inst.getBotAtivo())
                && inst.getConectadoEm() != null
                && cooldownOk(inst)
                && inst.getTentativasReconexaoSeguidas() < MAX_TENTATIVAS_SEGUIDAS) {

            log.warn("[WAHealth] instância {} em {} — disparando auto-reconexão",
                    inst.getInstanceName(), estado);
            // Marca o throttle ANTES do restart pra evitar race nas threads
            // do tick (loop sequencial, mas defensivo).
            ULTIMA_RECONEXAO_GLOBAL.set(LocalDateTime.now());
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
        } else if (precisaIntervir && throttleGlobalBloqueado) {
            log.info("[WAHealth] {} precisaria reconectar mas throttle global ({}s) ativo — aguardando próximo tick",
                    inst.getInstanceName(), THROTTLE_GLOBAL_SEGUNDOS);
        } else if (precisaIntervir && emWarmup) {
            log.info("[WAHealth] {} em WARMUP (conta com <{}h) — auto-reconexão desabilitada. Dono deve reconectar manualmente.",
                    inst.getInstanceName(), WARMUP_HORAS_CONTA_NOVA);
        } else if (precisaIntervir && emPicoOperacao) {
            log.info("[WAHealth] {} precisaria reconectar mas está em PICO ({}h) — postergado pra fora do horário crítico",
                    inst.getInstanceName(), horaAgora);
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
