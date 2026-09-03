package com.mydelivery.fiscal.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.fiscal.model.LogAuditoriaFiscal;

public interface LogAuditoriaFiscalRepository
        extends JpaRepository<LogAuditoriaFiscal, Long> {

    List<LogAuditoriaFiscal> findTop200ByRestauranteIdOrderByCriadoEmDesc(Long restauranteId);

    List<LogAuditoriaFiscal> findTop500ByOrderByCriadoEmDesc();
}
