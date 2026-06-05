package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.UsuarioGarcom;

public interface UsuarioGarcomRepository extends JpaRepository<UsuarioGarcom, Long> {

    List<UsuarioGarcom> findByRestauranteIdAndAtivoTrueOrderByNomeAsc(Long restauranteId);

    List<UsuarioGarcom> findByRestauranteIdOrderByNomeAsc(Long restauranteId);

    /** Login: pega TODOS ativos do restaurante (PIN é comparado em memória
     *  com BCrypt.matches — não dá pra fazer no banco). Lista é pequena
     *  (poucos garçons por loja), custo desprezível. */
    Optional<UsuarioGarcom> findFirstByRestauranteIdAndAtivoTrueAndId(Long restauranteId, Long id);
}
