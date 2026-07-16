package com.mydelivery.job;

import com.mydelivery.service.whatsapp.UazapiClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappInstanceRepository;

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
    private final UazapiClient evolutionClient;

    /**
     * Kill-switch. Padrão DESABILITADO — restart preventivo cego causou mais
     * problema que resolveu. Só ativa quando tiver certeza que sessão velha
     * é o problema real (evidência empírica, não teoria).
     */
    @org.springframework.beans.factory.annotation.Value("${mydelivery.jobs.wa-reconnect.ativo:false}")
    private boolean ativo;

    /** Intervalo mínimo entre restarts da MESMA instância. */
    private static final int MIN_HORAS_ENTRE_RESTARTS = 4;

    /**
     * Só faz restart preventivo se instância está SEM RECEBER MENSAGEM há
     * pelo menos N horas. Evidência real de "sessão travada".
     * Menor que isso = provavelmente saudável, só sem tráfego temporário.
     */
    private static final int HORAS_SEM_MSG_PRA_RESTART = 24;

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
    /**
     * MUDANÇA CRÍTICA Jul/2026: restart preventivo agora é OPT-IN via env var.
     * A hipótese original (Baileys entra em shadow ban silencioso se sessão
     * fica muito tempo aberta) NÃO SE PROVOU empiricamente — restart preventivo
     * em conta saudável causou mais dano do que resolveu:
     *
     *   - Cada restart é evento de "sessão nova" pro WhatsApp → suspeição
     *   - Instância que estava recebendo mensagens perdeu ~40s de tráfego
     *   - Se ban veio depois, restart em conta já frágil piorou
     *
     * Comportamento atual (opt-out por padrão):
     *   - Job só roda se MYDELIVERY_JOBS_WA_RECONNECT_ATIVO=true
     *   - Quando ativo, só restart em instância há >24h sem receber mensagem
     *     (evidência real de "está travada"), fora de pico, fora de warmup.
     *
     * Deixe DESATIVADO por padrão. Só liga se tiver evidência forte que
     * o problema é a sessão ficar velha (raro).
     */
    @Scheduled(fixedDelay = 60L * 60 * 1000, initialDelay = 5L * 60 * 1000)
    public void restartPreventivo() {
        if (!ativo) {
            log.debug("[WhatsappReconnectJob] desabilitado (MYDELIVERY_JOBS_WA_RECONNECT_ATIVO=false) — pulando");
            return;
        }

        LocalTime agora = LocalTime.now();
        if (estaNoPico(agora)) {
            log.info("[WhatsappReconnectJob] horário de pico ({}h) — pulando restart preventivo", agora.getHour());
            return;
        }

        List<WhatsappInstance> candidatas;
        try {
            candidatas = repo.findAll().stream()
                    .filter(i -> i.getStatus() == WhatsappInstance.Status.CONECTADA
                              && Boolean.TRUE.equals(i.getBotAtivo()))
                    // NOVO: só restart em instância "morta" — sem mensagem há
                    // muito tempo. Instância recebendo tráfego = saudável, não
                    // mexe. Isso é a diferença entre "restart preventivo cego"
                    // e "restart guiado por evidência".
                    .filter(i -> {
                        LocalDateTime ult = i.getUltimaMensagemRecebidaEm();
                        return ult != null
                                && Duration.between(ult, LocalDateTime.now()).toHours() >= HORAS_SEM_MSG_PRA_RESTART;
                    })
                    // Pula instâncias em warmup (< 48h desde 1a conexão).
                    // FIX Jul/2026: usa sessaoIniciadaEm em vez de conectadoEm
                    // (esse era resetado a cada reconexão, warmup nunca expirava).
                    .filter(i -> {
                        LocalDateTime ref = i.getSessaoIniciadaEm() != null
                                ? i.getSessaoIniciadaEm()
                                : i.getConectadoEm();
                        return ref == null
                                || Duration.between(ref, LocalDateTime.now()).toHours() >= 48;
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("[WhatsappReconnectJob] falha ao listar instâncias: {}", e.getMessage());
            return;
        }
        if (candidatas.isEmpty()) {
            log.debug("[WhatsappReconnectJob] sem candidatas a restart (todas saudáveis ou em warmup)");
            return;
        }

        int ok = 0, falha = 0, pulado = 0;
        LocalDateTime inicio = LocalDateTime.now();
        for (WhatsappInstance inst : candidatas) {
            LocalDateTime ultimo = inst.getUltimaTentativaReconexaoEm();
            if (ultimo != null
                    && Duration.between(ultimo, inicio).toHours() < MIN_HORAS_ENTRE_RESTARTS) {
                pulado++;
                continue;
            }

            try {
                evolutionClient.restart(inst.getInstanceName());
                inst.setUltimaTentativaReconexaoEm(LocalDateTime.now());
                repo.save(inst);
                ok++;
                log.info("[WhatsappReconnectJob] restart guiado por evidência — {} (rest={}, {}h sem msg)",
                        inst.getInstanceName(),
                        inst.getRestaurante() != null ? inst.getRestaurante().getId() : null,
                        inst.getUltimaMensagemRecebidaEm() != null
                            ? Duration.between(inst.getUltimaMensagemRecebidaEm(), LocalDateTime.now()).toHours()
                            : -1);
            } catch (Exception e) {
                falha++;
                log.warn("[WhatsappReconnectJob] restart falhou pra {}: {}",
                        inst.getInstanceName(), e.getMessage());
            }
        }
        log.info("[WhatsappReconnectJob] tick — candidatas={} ok={} falha={} pulado={} dur={}s",
                candidatas.size(), ok, falha, pulado,
                Duration.between(inicio, LocalDateTime.now()).getSeconds());
    }

    private boolean estaNoPico(LocalTime hora) {
        int h = hora.getHour();
        return h >= PICO_HORA_INICIO && h <= PICO_HORA_FIM;
    }
}
