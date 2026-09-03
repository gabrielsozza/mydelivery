package com.mydelivery.fiscal.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.fiscal.model.PerfilFiscalProduto;

public interface PerfilFiscalProdutoRepository
        extends JpaRepository<PerfilFiscalProduto, Long> {
    Optional<PerfilFiscalProduto> findByProdutoId(Long produtoId);
}
