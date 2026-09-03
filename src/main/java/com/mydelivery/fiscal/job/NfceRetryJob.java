package com.mydelivery.fiscal.job;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mydelivery.fiscal.model.NotaFiscalEmitida;
import com.mydelivery.fiscal.repository.NotaFiscalEmitidaRepository;
import com.mydelivery.fiscal.service.NfceEmissorService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Job que retransmite/retenta notas fiscais em problema:
 * <ul>
 *   <li>{@code CONTINGENCIA_EPEC} — emitidas offline, precisam ser retransmitidas
 *       quando a SEFAZ voltar</li>
 *   <li>{@code REJEITADA} (transitório) — falha de rede/SEFAZ paralisada, vale
 *       tentar de novo com backoff exponencial</li>
 * </ul>
 *
 * <p>Roda a cada 5 min. Só toca em notas com {@code proximaTentativaEm} vencida.
 * Cap de 8 tentativas por nota — depois disso abandona (dono resolve na aba).
 *
 * <p>Desligado por default. Ligar com {@code mydelivery.fiscal.retry.ativo=true}
 * no Railway quando módulo fiscal for pra produção.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NfceRetryJob {

    private final NotaFiscalEmitidaRepository notaRepo;
    private final NfceEmissorService emissor;

    @Value("${mydelivery.fiscal.retry.ativo:false}")
    private boolean ativo;

    /**
     * A cada 5 min: pega notas com {@code proximaTentativaEm <= agora} e status
     * elegível pra retry. Processa uma por vez pra não estourar rede/pool. Um
     * "loop guard" evita reprocessar a mesma nota se o job atrasar (rare edge).
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void rodar() {
        if (!ativo) return;
        LocalDateTime agora = LocalDateTime.now();
        List<NotaFiscalEmitida> contingencia = notaRepo.findByStatusAndProximaTentativaEmBefore(
                NotaFiscalEmitida.Status.CONTINGENCIA_EPEC, agora);
        List<NotaFiscalEmitida> rejeitadas = notaRepo.findByStatusAndProximaTentativaEmBefore(
                NotaFiscalEmitida.Status.REJEITADA, agora);
        if (contingencia.isEmpty() && rejeitadas.isEmpty()) return;

        log.info("[Fiscal][RetryJob] rodando — contingencia={} rejeitadas={}",
                contingencia.size(), rejeitadas.size());

        Set<Long> processados = new HashSet<>();
        for (var n : contingencia) tryOnce(n, processados);
        for (var n : rejeitadas)   tryOnce(n, processados);
    }

    private void tryOnce(NotaFiscalEmitida n, Set<Long> processados) {
        if (n == null || n.getId() == null) return;
        if (!processados.add(n.getId())) return;   // evita duplicata na mesma execução
        try {
            emissor.retentarNota(n.getId());
        } catch (Exception e) {
            log.warn("[Fiscal][RetryJob] Exception retentando nota {}: {}", n.getId(), e.getMessage());
        }
    }
}
