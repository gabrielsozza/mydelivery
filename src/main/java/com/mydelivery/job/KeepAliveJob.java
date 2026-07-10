package com.mydelivery.job;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.UazapiClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Keep-alive global.
 *
 * <p><b>Problema que resolve:</b> Railway hiberna o container apos ~5min
 * sem requests HTTP. Cold-start subsequente leva 30-60s. Como restaurante
 * abre de manha e o primeiro cliente manda mensagem no WhatsApp logo,
 * essa mensagem caia no cold-start e o bot demorava 30s+ pra responder
 * (em vez dos 3-5s normais que ja vinham configurados via throttle).
 *
 * <p><b>O que faz a cada 4 minutos:</b>
 * <ol>
 *   <li>Toca o banco com 1 query leve (count em whatsapp_instances) —
 *       mantem o DataSource pool quente e impede Railway de hibernar
 *       o container (JVM ativa = sem hibernate).
 *   <li>Pra cada instancia CONECTADA, pinga
 *       {@link EvolutionClient#consultarStatus(String)} — mantem a
 *       sessao Baileys da Evolution ativa, evita ela entrar em idle e
 *       perder a primeira mensagem do dia.
 * </ol>
 *
 * <p>Intervalo = 4 minutos: Railway hiberna em ~5min de inatividade, 4min
 * deixa folga sem desperdicar recursos. Custo: 1 query/min + N pings
 * Evolution onde N = restaurantes com WhatsApp conectado.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeepAliveJob {

    private final WhatsappInstanceRepository whatsappRepo;
    private final UazapiClient evolutionClient;

    /**
     * Roda a cada 4 minutos. {@code initialDelay} = 2min pra nao bater junto
     * com outros jobs no boot e dar tempo da app estabilizar.
     */
    @Scheduled(fixedRate = 4L * 60_000L, initialDelay = 2L * 60_000L)
    @Transactional(readOnly = true)
    public void tick() {
        long t0 = System.currentTimeMillis();
        int totalInstancias;
        int pingadasOk = 0;
        int pingadasErro = 0;

        try {
            // 1) Toque no banco — mantem DataSource pool ativo + Railway acordado.
            //    count() em tabela pequena, sem JOIN.
            long count = whatsappRepo.count();
            totalInstancias = (int) count;
        } catch (RuntimeException e) {
            log.warn("[KeepAlive] db tick falhou: {}", e.getMessage());
            return;
        }

        // 2) Ping Evolution apenas pras instancias CONECTADA. Pingar
        //    AGUARDANDO_QR/DESCONECTADA/ERRO seria desperdicio e ja tem o
        //    WhatsappReconnectJob cobrindo recovery delas.
        try {
            List<WhatsappInstance> conectadas = whatsappRepo
                    .findAllByStatus(WhatsappInstance.Status.CONECTADA);
            for (WhatsappInstance inst : conectadas) {
                try {
                    evolutionClient.consultarStatus(inst.getInstanceName());
                    pingadasOk++;
                } catch (RuntimeException e) {
                    pingadasErro++;
                    // Nao loga cada falha — WhatsappHealthJob cuida disso.
                }
            }
        } catch (RuntimeException e) {
            log.warn("[KeepAlive] varredura de instancias falhou: {}", e.getMessage());
        }

        long ms = System.currentTimeMillis() - t0;
        log.debug("[KeepAlive] tick em {}ms — total={} pingOk={} pingErr={}",
                ms, totalInstancias, pingadasOk, pingadasErro);
    }
}
