package com.mydelivery.job;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mydelivery.model.MesaSessao;
import com.mydelivery.repository.MesaSessaoRepository;
import com.mydelivery.service.WebPushService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * "Mesa esquecida" — diferencial real do produto.
 *
 * Roda a cada 2 minutos. Pra cada sessão ABERTA cuja ultima_interacao_em
 * passou de N minutos, envia Web Push pro restaurante (chega no celular do
 * dono/garçom mesmo com tela bloqueada).
 *
 * Threshold escolhido baseado em pesquisa de campo:
 *  - 20min sem interação em mesa ocupada = sinal de que ninguém passou.
 *  - 40min = crítico, escala severidade (próxima geração de incidente).
 *
 * Dedup por sessão: cada sessão só dispara push UMA vez por nível.
 * Quando garçom interage, ultimaInteracaoEm atualiza e o ciclo reseta.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MesaEsquecidaJob {

    private final MesaSessaoRepository sessaoRepo;
    private final WebPushService webPush;

    private static final int MIN_AVISO = 20;
    private static final int MIN_CRITICO = 40;

    /**
     * Dedup em memória: sessaoId → último nível notificado.
     * Evita spam mesmo se o job rodar de novo. Não persistido — quando
     * backend reinicia, todos voltam pra zero, e no próximo tick re-notifica.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> notificadas = new java.util.concurrent.ConcurrentHashMap<>();

    @Scheduled(fixedRate = 2 * 60_000L, initialDelay = 90_000L)
    public void tick() {
        LocalDateTime corte = LocalDateTime.now().minusMinutes(MIN_AVISO);
        var sessoes = sessaoRepo.findByFechamentoEmIsNullAndUltimaInteracaoEmLessThan(corte);
        if (sessoes.isEmpty()) return;
        log.debug("[MesaEsquecida] avaliando {} sessões com >= {}min sem interação", sessoes.size(), MIN_AVISO);

        for (MesaSessao s : sessoes) {
            try {
                avaliar(s);
            } catch (Exception e) {
                log.warn("[MesaEsquecida] erro em sessao={}: {}", s.getId(), e.getMessage());
            }
        }
        // Limpa cache: sessões já fechadas ou com interação recente saem do mapa
        notificadas.entrySet().removeIf(e -> {
            return sessoes.stream().noneMatch(x -> x.getId().equals(e.getKey()));
        });
    }

    private void avaliar(MesaSessao s) {
        long min = Duration.between(s.getUltimaInteracaoEm(), LocalDateTime.now()).toMinutes();
        int nivelAtual = min >= MIN_CRITICO ? 2 : (min >= MIN_AVISO ? 1 : 0);
        if (nivelAtual == 0) return;

        Integer ultimo = notificadas.get(s.getId());
        if (ultimo != null && ultimo >= nivelAtual) return; // já avisou nesse nível ou maior

        String mesaNome = s.getMesa() != null ? s.getMesa().getNome() : "Mesa";
        String titulo, corpo;
        if (nivelAtual == 2) {
            titulo = "🔴 Mesa crítica sem atendimento";
            corpo = mesaNome + " · " + min + "min sem interação. Atender AGORA.";
        } else {
            titulo = "🟡 Mesa pedindo atenção";
            corpo = mesaNome + " · " + min + "min sem interação. Passar lá.";
        }
        try {
            webPush.notificar(s.getRestauranteId(), titulo, corpo,
                    "/garcom.html", "mesa-esquecida-" + s.getId());
            notificadas.put(s.getId(), nivelAtual);
            log.info("[MesaEsquecida] notificado nivel={} sessao={} mesa={} min={}",
                    nivelAtual, s.getId(), mesaNome, min);
        } catch (Exception e) {
            log.warn("[MesaEsquecida] push falhou: {}", e.getMessage());
        }
    }
}
