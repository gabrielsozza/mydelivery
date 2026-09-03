package com.mydelivery.fiscal.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.fiscal.model.CertificadoDigital;

public interface CertificadoDigitalRepository extends JpaRepository<CertificadoDigital, Long> {

    /** Certificado ativo do restaurante (só 1 por vez — quando sobe novo, o
     *  antigo é marcado ativo=false, ficando como histórico). */
    Optional<CertificadoDigital> findByRestauranteIdAndAtivoTrue(Long restauranteId);
}
