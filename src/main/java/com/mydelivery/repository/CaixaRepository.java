package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mydelivery.model.Caixa;

public interface CaixaRepository extends JpaRepository<Caixa, Long> {

    /**
     * Retorna o caixa aberto do restaurante (0 ou 1 — a lógica de negócio
     * garante que só um pode estar aberto). Se a Fase 2 do módulo permitir
     * múltiplos caixas por PDV, esse método vira {@code findByStatusAndTerminal}.
     */
    Optional<Caixa> findFirstByRestauranteIdAndStatus(Long restauranteId, Caixa.Status status);

    /** Histórico ordenado do mais recente ao mais antigo. */
    List<Caixa> findByRestauranteIdOrderByAbertoEmDesc(Long restauranteId);

    /** Histórico com filtro de data (aberto_em >= desde). Usado pela tela. */
    @Query("""
        SELECT c FROM Caixa c
        WHERE c.restaurante.id = :restauranteId
          AND c.abertoEm >= :desde
        ORDER BY c.abertoEm DESC
    """)
    List<Caixa> historicoDesde(@Param("restauranteId") Long restauranteId,
                                @Param("desde") LocalDateTime desde);
}
