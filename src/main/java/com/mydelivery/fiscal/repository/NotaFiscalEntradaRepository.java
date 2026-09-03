package com.mydelivery.fiscal.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.fiscal.model.NotaFiscalEntrada;

public interface NotaFiscalEntradaRepository
        extends JpaRepository<NotaFiscalEntrada, Long> {

    Optional<NotaFiscalEntrada> findByChaveAcesso(String chaveAcesso);

    List<NotaFiscalEntrada> findByRestauranteIdOrderByDataEmissaoDesc(Long restauranteId);

    List<NotaFiscalEntrada> findByRestauranteIdAndDataEmissaoBetween(
            Long restauranteId, LocalDateTime inicio, LocalDateTime fim);
}
