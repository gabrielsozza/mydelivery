package com.mydelivery.fiscal.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.fiscal.model.NotaFiscalEmitida;

public interface NotaFiscalEmitidaRepository
        extends JpaRepository<NotaFiscalEmitida, Long> {

    Optional<NotaFiscalEmitida> findByChaveAcesso(String chave);

    List<NotaFiscalEmitida> findByRestauranteIdOrderByCriadoEmDesc(Long restauranteId);

    List<NotaFiscalEmitida> findByPedidoId(Long pedidoId);

    /** Notas em retry (rejeição transitória / contingência). */
    List<NotaFiscalEmitida> findByStatusAndProximaTentativaEmBefore(
            NotaFiscalEmitida.Status status, LocalDateTime agora);

    /** Notas em contingência que precisam retransmitir. */
    List<NotaFiscalEmitida> findByStatus(NotaFiscalEmitida.Status status);

    /** Sonda usada pelo reservarProximoNumero pra pular numeros ja emitidos. */
    boolean existsByCnpjAndSerieAndAmbienteAndModeloAndNumero(
            String cnpj, Integer serie, Integer ambiente, Integer modelo, Long numero);
}
