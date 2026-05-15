package com.mydelivery.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.CupomUso;

public interface CupomUsoRepository extends JpaRepository<CupomUso, Long> {

    long countByCupomId(Long cupomId);

    long countByCupomIdAndTelefoneCliente(Long cupomId, String telefoneCliente);
}
