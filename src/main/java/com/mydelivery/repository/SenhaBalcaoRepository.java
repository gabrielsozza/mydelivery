package com.mydelivery.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.SenhaBalcao;

public interface SenhaBalcaoRepository extends JpaRepository<SenhaBalcao, Long> {

    /** Maior número emitido HOJE pra esse restaurante — base do próximo sequencial. */
    Optional<SenhaBalcao> findFirstByRestauranteIdAndDataEmissaoOrderByNumeroDesc(
            Long restauranteId, LocalDate dataEmissao);

    /** Lookup por pedido (impressão térmica precisa saber qual senha foi). */
    Optional<SenhaBalcao> findByPedidoId(Long pedidoId);

    /** Senhas emitidas hoje — usado pelo painel de chamada (Fase 4). */
    List<SenhaBalcao> findByRestauranteIdAndDataEmissaoOrderByNumeroAsc(
            Long restauranteId, LocalDate dataEmissao);
}
