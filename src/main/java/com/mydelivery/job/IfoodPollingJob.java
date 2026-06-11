package com.mydelivery.job;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mydelivery.config.IfoodProperties;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.ifood.IfoodClient;
import com.mydelivery.service.ifood.IfoodService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Polling de eventos do iFood. Roda a cada 30s (configurável via prop).
 *
 * Estratégia:
 *  1. Se nenhum restaurante tem integração ativa → não chama Evolution.
 *  2. Faz GET /events:polling — devolve todos os eventos pendentes
 *     pra todos os merchants ligados ao nosso app (não é por merchant).
 *  3. Processa cada evento via IfoodService:
 *     - Match do merchantId → Restaurante local
 *     - Cria/atualiza Pedido
 *  4. ACK em batch dos eventos processados (livra fila do iFood).
 *
 * O props.pollingAtivo=false desabilita o job completamente — útil
 * enquanto o app não foi homologado pelo iFood.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IfoodPollingJob {

    private final IfoodProperties props;
    private final IfoodClient client;
    private final IfoodService ifoodService;
    private final RestauranteRepository restauranteRepo;

    /**
     * fixedDelay = 30s. initialDelay = 30s (boot folgado, OAuth pode demorar).
     * Polling iFood exige no mínimo 30s entre chamadas — não diminuir.
     */
    @Scheduled(fixedDelayString = "${mydelivery.ifood.polling-intervalo-segundos:30}000",
               initialDelay = 30_000L)
    public void poll() {
        if (!props.isPollingAtivo()) {
            log.debug("[iFood-Polling] desligado (pollingAtivo=false)");
            return;
        }
        if (props.getClientId() == null || props.getClientId().isBlank()
                || props.getClientSecret() == null || props.getClientSecret().isBlank()) {
            log.debug("[iFood-Polling] credenciais não configuradas — pulando");
            return;
        }

        // Sem restaurantes integrados → não consome cota iFood inutilmente
        List<com.mydelivery.model.Restaurante> ativos =
                restauranteRepo.findByIfoodIntegracaoAtivaTrueAndIfoodMerchantIdIsNotNull();
        if (ativos.isEmpty()) {
            log.debug("[iFood-Polling] nenhum restaurante ativo");
            return;
        }

        long inicio = System.currentTimeMillis();
        List<Map<String, Object>> eventos;
        try {
            eventos = client.pollingEventos();
        } catch (Exception e) {
            log.warn("[iFood-Polling] falhou: {}", e.getMessage());
            return;
        }
        if (eventos == null || eventos.isEmpty()) {
            // Atualiza timestamp de last polling pra mostrar "tudo OK" no painel
            for (var r : ativos) r.setIfoodUltimoPollingEm(LocalDateTime.now());
            restauranteRepo.saveAll(ativos);
            log.debug("[iFood-Polling] sem eventos pendentes");
            return;
        }

        List<String> idsParaAck = new ArrayList<>();
        int ok = 0, falha = 0;
        for (Map<String, Object> ev : eventos) {
            String id = (String) ev.get("id");
            try {
                boolean processado = ifoodService.processarEvento(ev);
                if (processado) {
                    if (id != null) idsParaAck.add(id);
                    ok++;
                } else {
                    falha++;
                }
            } catch (Exception e) {
                log.error("[iFood-Polling] erro evento {}: {}", id, e.getMessage(), e);
                falha++;
            }
        }

        if (!idsParaAck.isEmpty()) {
            client.acknowledgeEventos(idsParaAck);
        }

        // Atualiza last polling em todos os restaurantes ativos
        LocalDateTime agora = LocalDateTime.now();
        for (var r : ativos) r.setIfoodUltimoPollingEm(agora);
        restauranteRepo.saveAll(ativos);

        long durMs = System.currentTimeMillis() - inicio;
        log.info("[iFood-Polling] tick — eventos={} ok={} falha={} ack={} dur={}ms",
                eventos.size(), ok, falha, idsParaAck.size(), durMs);
    }
}
