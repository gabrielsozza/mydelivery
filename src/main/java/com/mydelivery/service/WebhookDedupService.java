package com.mydelivery.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.WebhookEventoProcessado;
import com.mydelivery.repository.WebhookEventoProcessadoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dedup atômico de webhooks externos. Usado por
 * {@code MercadoPagoWebhookController} e {@code WhatsappWebhookController}
 * pra evitar processar o mesmo evento 2x.
 *
 * <p>Como funciona: tenta INSERT na tabela {@code webhook_eventos_processados}.
 * Se rolar {@link DataIntegrityViolationException} (unique violado), é
 * duplicata — devolve {@code false} e o handler deve responder 200 OK
 * sem reprocessar. Caso contrário, {@code true}.
 *
 * <p>Limpeza: registros antigos podem ser purgados via job
 * {@code @Scheduled(cron="0 0 4 * * *")} (não criado ainda — adicionar
 * quando a tabela passar de ~1M linhas).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDedupService {

    private final WebhookEventoProcessadoRepository repo;

    /**
     * Tenta marcar o evento como processado. REQUIRES_NEW pra que a
     * exception de unique não polua a transaction atual do handler.
     *
     * @param origem    "mercadopago" | "evolution" | etc.
     * @param idExterno identificador único do evento. Se null/blank, retorna
     *                  true sem deduplicar (sem id não dá pra detectar duplicata).
     * @return true se foi a primeira vez (processar); false se ja existia.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaim(String origem, String idExterno) {
        if (idExterno == null || idExterno.isBlank()) {
            // Sem ID nao da pra dedup. Processa (provider mal-comportado).
            return true;
        }
        try {
            repo.save(WebhookEventoProcessado.builder()
                    .origem(origem)
                    .idExterno(idExterno)
                    .build());
            return true;
        } catch (DataIntegrityViolationException dupe) {
            log.info("[WebhookDedup] duplicata ignorada: origem={} id={}", origem, idExterno);
            return false;
        }
    }
}
