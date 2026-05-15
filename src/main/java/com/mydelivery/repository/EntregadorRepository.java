package com.mydelivery.repository;

import com.mydelivery.model.Entregador;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EntregadorRepository extends JpaRepository<Entregador, Long> {
    List<Entregador> findByRestauranteIdAndAtivoTrue(Long restauranteId);
}
