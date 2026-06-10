package com.mydelivery.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.EvolutionClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Warmup leve — mantém DB pool, classes Hibernate e canal HTTP com a
 * Evolution "quentes" pra eliminar cold start na 1ª mensagem do bot.
 *
 * Antes desse job:
 *  - Após período sem atividade, a primeira msg do cliente sofria com
 *    HikariCP recriando conexão (~300ms), Hibernate compilando query
 *    (~100ms) e TLS handshake com a Evolution (~300ms). Total: 700ms
 *    DESNECESSÁRIOS, somados ao processamento real.
 *
 * Como funciona:
 *  - A cada 90s (abaixo do typical idle timeout do HikariCP, 10 min) faz:
 *    (1) findAll().limit(1) — bate no DB, mantém pool aberto
 *    (2) GET /instance/connectionState em uma instância ativa — TLS quente
 *
 * Custo: 1 query + 1 HTTP/s a cada 90s. Imperceptível.
 * Benefício: latência da 1ª mensagem cai de ~3s pra <1s (warm).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsappWarmupJob {

    private final WhatsappInstanceRepository repo;
    private final EvolutionClient evolutionClient;

    @Scheduled(fixedRate = 90_000L, initialDelay = 60_000L)
    public void warmup() {
        try {
            // (1) Toca no DB pra manter pool HikariCP quente.
            // findAll() é leve aqui — tipicamente <50 instâncias no sistema.
            var todas = repo.findAll();

            // (2) Pega 1 instância CONECTADA e bate no connectionState pra
            // manter TLS + Evolution responsivo. Não precisa fazer em todas
            // — basta uma pra keep-alive.
            WhatsappInstance alvo = todas.stream()
                    .filter(i -> i.getStatus() == WhatsappInstance.Status.CONECTADA)
                    .findFirst()
                    .orElse(null);

            if (alvo != null) {
                try {
                    evolutionClient.consultarStatus(alvo.getInstanceName());
                } catch (Exception e) {
                    // Erro de rede não é problema — próximo tick tenta.
                    log.debug("[WA-Warmup] ping Evolution falhou: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[WA-Warmup] tick falhou: {}", e.getMessage());
        }
    }
}
