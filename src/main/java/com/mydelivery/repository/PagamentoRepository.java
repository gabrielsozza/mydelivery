package com.mydelivery.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.Pagamento;

public interface PagamentoRepository extends JpaRepository<Pagamento, Long> {

    Optional<Pagamento> findByPedidoId(Long pedidoId);

    /** Lookup do webhook: MP envia paymentId, precisamos achar o Pagamento (e por ele o tenant). */
    Optional<Pagamento> findByMpPaymentId(Long mpPaymentId);

    /** Recuperação por chave de idempotência (caso o registro local exista mas mpPaymentId tenha falhado de salvar). */
    Optional<Pagamento> findByMpIdempotencyKey(String key);
}
