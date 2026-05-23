package com.mydelivery.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.PagamentoMensalidade;

public interface PagamentoMensalidadeRepository extends JpaRepository<PagamentoMensalidade, Long> {
}
