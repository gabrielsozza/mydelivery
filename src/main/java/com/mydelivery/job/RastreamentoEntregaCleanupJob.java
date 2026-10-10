package com.mydelivery.job;

import com.mydelivery.repository.PedidoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Remove coordenadas, tokens e hashes de confirmação após o prazo da entrega. */
@Component
@Slf4j
@RequiredArgsConstructor
public class RastreamentoEntregaCleanupJob {
    private final PedidoRepository pedidos;

    @Scheduled(fixedDelay = 60 * 60 * 1000L, initialDelay = 60 * 60 * 1000L)
    @Transactional
    public void limparDadosExpirados() {
        LocalDateTime agora = LocalDateTime.now();
        int locais = pedidos.limparLocalizacaoExpirada(agora);
        int codigos = pedidos.limparCodigosExpirados(agora);
        if (locais + codigos > 0) log.info("[Rastreamento] dados temporários expirados removidos — localizações={}, códigos={}", locais, codigos);
    }
}
