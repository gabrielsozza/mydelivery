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
}
