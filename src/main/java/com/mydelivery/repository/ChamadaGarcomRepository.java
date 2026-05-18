package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.ChamadaGarcom;

public interface ChamadaGarcomRepository extends JpaRepository<ChamadaGarcom, Long> {

    List<ChamadaGarcom> findByRestauranteIdAndStatusOrderByCriadaEmAsc(Long restauranteId, String status);

    /** Última chamada PENDENTE da mesa — usado pra dedup (evita botão spam). */
    Optional<ChamadaGarcom> findFirstByMesaIdAndStatusOrderByCriadaEmDesc(Long mesaId, String status);
}
