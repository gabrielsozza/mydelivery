package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.PagamentoMensalidade;

public interface PagamentoMensalidadeRepository extends JpaRepository<PagamentoMensalidade, Long> {

    /** Últimos pagamentos de um restaurante em ordem decrescente (histórico da aba planos). */
    List<PagamentoMensalidade> findTop12ByRestauranteIdOrderByCriadoEmDesc(Long restauranteId);

    /**
     * Existe algum PagamentoMensalidade com esse mpPaymentId no status PAGO?
     * Usado pra idempotência da reconciliação — evita criar linha duplicada
     * quando admin dispara /reconciliar-pagamento-admin múltiplas vezes.
     */
    boolean existsByMpPaymentIdAndStatus(Long mpPaymentId, PagamentoMensalidade.Status status);

    /**
     * Busca pagamento por mpPaymentId — usado no upsert PENDENTE → PAGO
     * quando o PIX aprovado chega via webhook. Se existir linha PENDENTE
     * criada em {@code criarPix}, ela é promovida em vez de inserir nova.
     */
    Optional<PagamentoMensalidade> findByMpPaymentId(Long mpPaymentId);
}
