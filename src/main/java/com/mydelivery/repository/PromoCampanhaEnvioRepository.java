package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.mydelivery.model.PromoCampanhaEnvio;

public interface PromoCampanhaEnvioRepository extends JpaRepository<PromoCampanhaEnvio, Long> {

    /** Pendentes de uma campanha — usado pelo sender pra escolher o próximo lote.
     *  Ordenado por id (FIFO) e limitado externamente (PageRequest). */
    @Query("SELECT e FROM PromoCampanhaEnvio e WHERE e.campanha.id = :campanhaId " +
           "AND e.enviadoEm IS NULL ORDER BY e.id ASC")
    List<PromoCampanhaEnvio> findPendentes(Long campanhaId, PageRequest limit);

    int countByCampanhaId(Long campanhaId);
    int countByCampanhaIdAndSucessoTrue(Long campanhaId);
    int countByCampanhaIdAndSucessoFalse(Long campanhaId);
    int countByCampanhaIdAndEnviadoEmIsNull(Long campanhaId);
}
