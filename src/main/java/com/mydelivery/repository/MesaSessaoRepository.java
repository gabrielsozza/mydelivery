package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.MesaSessao;

public interface MesaSessaoRepository extends JpaRepository<MesaSessao, Long> {

    /** Sessão aberta de UMA mesa específica (fechamento_em IS NULL). */
    Optional<MesaSessao> findFirstByMesaIdAndFechamentoEmIsNullOrderByAberturaEmDesc(Long mesaId);

    /** TODAS as sessões abertas de um restaurante — base do mapa do salão. */
    List<MesaSessao> findByRestauranteIdAndFechamentoEmIsNullOrderByAberturaEmAsc(Long restauranteId);

    /** Histórico de sessões fechadas pra relatórios. */
    List<MesaSessao> findByRestauranteIdAndFechamentoEmIsNotNullAndAberturaEmGreaterThanEqualOrderByAberturaEmDesc(
            Long restauranteId, LocalDateTime desde);

    /** Sessões abertas há mais de N tempo SEM interação — base do alerta de
     *  mesa esquecida (job a cada 2min). */
    List<MesaSessao> findByFechamentoEmIsNullAndUltimaInteracaoEmLessThan(LocalDateTime corte);

    /** Sessões abertas de uma mesa — usado no DELETE de mesa pra bloquear
     *  exclusão com comanda em andamento. */
    List<MesaSessao> findByMesaIdAndFechamentoEmIsNull(Long mesaId);

    /** Apaga TODAS as sessões (fechadas incluídas) de uma mesa — chamado
     *  no DELETE /api/mesas/{id} depois de garantir que não há aberta. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM MesaSessao s WHERE s.mesa.id = :mesaId")
    void deleteByMesaId(@org.springframework.data.repository.query.Param("mesaId") Long mesaId);
}
