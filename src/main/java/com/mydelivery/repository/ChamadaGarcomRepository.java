package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.ChamadaGarcom;

public interface ChamadaGarcomRepository extends JpaRepository<ChamadaGarcom, Long> {

    List<ChamadaGarcom> findByRestauranteIdAndStatusOrderByCriadaEmAsc(Long restauranteId, String status);

    /** Última chamada PENDENTE da mesa — usado pra dedup (evita botão spam). */
    Optional<ChamadaGarcom> findFirstByMesaIdAndStatusOrderByCriadaEmDesc(Long mesaId, String status);

    /** Apaga todas chamadas de uma mesa — usado no DELETE de mesa
     *  pra soltar a FK NOT NULL antes de deletar a mesa em si. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM ChamadaGarcom c WHERE c.mesa.id = :mesaId")
    void deleteByMesaId(@org.springframework.data.repository.query.Param("mesaId") Long mesaId);
}
