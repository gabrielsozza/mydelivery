package com.mydelivery.job;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.EvolutionClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Restart preventivo das sessões WhatsApp.
 *
 * MOTIVAÇÃO: WhatsApp aplica shadow-ban silencioso depois de algumas horas em
 * instâncias persistentes — Evolution mostra "open" mas para de entregar
 * MESSAGES_UPSERT. Refrescar a sessão via /instance/restart força o Baileys
 * a reconectar, frequentemente destravando entrega.
 *
 * ── EVOLUÇÃO Jun/2026 ──
 * Antes: 6h fixo, sem critério de horário. Cliente reportou queda 2x no
 * mesmo dia em horário de pico (jantar de steakhouse). Causa raiz: o restart
 * caía dentro da janela 18-23h e derrubava o bot na hora crítica.
 *
 * Agora:
 *  - Job roda a cada hora, mas só executa restart se passou >= 4h da última
 *    rodada dessa instância E hora atual está FORA do pico (18-23h).
 *  - Jitter de 0-60min na decisão de cada instância pra que 50 restaurantes
 *    não sejam restartados ao mesmo tempo (evita "thundering herd" no Evolution).
 *  - Por instância: a próxima rodada usa offset baseado no ID — naturalmente
 *    espalha as reciclagens ao longo do dia.
 *
 * Não exige re-pareamento. Mantém o número logado.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsappReconnectJob {

    private final WhatsappInstanceRepository repo;
    private final EvolutionClient evolutionClient;

    /** Intervalo mínimo entre restarts da MESMA instância. */
    private static final int MIN_HORAS_ENTRE_RESTARTS = 4;

    /** Hora inicial da janela de pico (não restartar). */
    private static final int PICO_HORA_INICIO = 18;
    /** Hora final da janela de pico. Inclusive — só libera às 23h. */
    private static final int PICO_HORA_FIM = 23;

    /** Jitter máximo: minutos aleatórios a esperar antes de restartar uma
     *  instância elegível. Espalha cargas no Evolution. */
    private static final int JITTER_MINUTOS_MAX = 30;

    /**
     * Tick horário (em vez de fixo 6h). A decisão de restartar OU NÃO cada
     * instância é tomada por instância dentro do tick.
     */
    @Scheduled(fixedDelay = 60L * 60 * 1000, initialDelay = 5L * 60 * 1000)
    public void restartPreventivo() {
        LocalTime agora = LocalTime.now();
        if (estaNoPico(agora)) {
            log.info("[WhatsappReconnectJob] horário de pico ({}h) — pulando restart preventivo", agora.getHour());
            return;
        }

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
            log.debug("[WhatsappReconnectJob] sem instâncias ativas");
            return;
        }

        int ok = 0, falha = 0, pulado = 0;
        LocalDateTime inicio = LocalDateTime.now();
        for (WhatsappInstance inst : ativas) {
            // Pula se foi restartada recentemente (menos de MIN_HORAS_ENTRE_RESTARTS).
            // Usa ultimaTentativaReconexaoEm como timestamp do último restart
            // (job e health service ambos atualizam esse campo).
            LocalDateTime ultimo = inst.getUltimaTentativaReconexaoEm();
            if (ultimo != null
                    && Duration.between(ultimo, inicio).toHours() < MIN_HORAS_ENTRE_RESTARTS) {
                pulado++;
                continue;
            }

            // Jitter: aplica delay aleatório curto pra evitar restart simultâneo
            // de N instâncias (thundering herd no Evolution). 0 a 30min em
            // chamada bloqueante seria ruim — fazemos o jitter via "sorteio
            // de elegibilidade": com prob = (horasAtual - 4) / (24 - 4), maior
            // prob de restartar conforme mais tempo passou.
            //
            // Resultado prático: instância que caiu há 4h tem ~15% de chance
            // de ser restartada nesse tick. Há 8h: ~30%. Há 12h: ~45%.
            // Distribui o restart ao longo de várias horas em vez de todo
            // mundo no mesmo minuto.
            double horasDesdeUltimo = ultimo == null ? 24
                    : Duration.between(ultimo, inicio).toHours();
            double prob = Math.min(1.0, (horasDesdeUltimo - MIN_HORAS_ENTRE_RESTARTS) / 12.0);
            if (ThreadLocalRandom.current().nextDouble() > prob) {
                pulado++;
                continue;
            }

            try {
                evolutionClient.restart(inst.getInstanceName());
                inst.setUltimaTentativaReconexaoEm(LocalDateTime.now());
                repo.save(inst);
                ok++;
                log.info("[WhatsappReconnectJob] restart ok — {} (rest={}, horasDesdeUlt={})",
                        inst.getInstanceName(),
                        inst.getRestaurante() != null ? inst.getRestaurante().getId() : null,
                        (int) horasDesdeUltimo);
            } catch (Exception e) {
                falha++;
                log.warn("[WhatsappReconnectJob] restart falhou pra {}: {}",
                        inst.getInstanceName(), e.getMessage());
            }
        }
        log.info("[WhatsappReconnectJob] tick — total={} ok={} falha={} pulado={} dur={}s",
                ativas.size(), ok, falha, pulado,
                Duration.between(inicio, LocalDateTime.now()).getSeconds());
    }

    private boolean estaNoPico(LocalTime hora) {
        int h = hora.getHour();
        return h >= PICO_HORA_INICIO && h <= PICO_HORA_FIM;
    }
}
