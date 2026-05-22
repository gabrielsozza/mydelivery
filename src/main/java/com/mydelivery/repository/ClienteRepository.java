package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.Cliente;

public interface ClienteRepository extends JpaRepository<Cliente, Long> {
    Optional<Cliente> findByTelefoneAndRestauranteId(String telefone, Long restauranteId);

    /** Lista todos os clientes de um restaurante (usado pela aba Clientes). */
    List<Cliente> findByRestauranteIdOrderByNomeAsc(Long restauranteId);
}