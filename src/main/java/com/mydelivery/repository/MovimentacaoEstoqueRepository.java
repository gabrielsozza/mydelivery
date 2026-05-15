package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.MovimentacaoEstoque;

public interface MovimentacaoEstoqueRepository extends JpaRepository<MovimentacaoEstoque, Long> {

    List<MovimentacaoEstoque> findByInsumoIdOrderByCriadoEmDesc(Long insumoId);

    List<MovimentacaoEstoque> findByInsumoRestauranteIdOrderByCriadoEmDesc(Long restauranteId);

    /** Usado pelo endpoint de relatório com filtro de período. */
    List<MovimentacaoEstoque> findByInsumoRestauranteIdAndCriadoEmBetweenOrderByCriadoEmDesc(
            Long restauranteId, LocalDateTime ini, LocalDateTime fim);
}
