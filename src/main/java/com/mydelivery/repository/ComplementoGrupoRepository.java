package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.ComplementoGrupo;

public interface ComplementoGrupoRepository extends JpaRepository<ComplementoGrupo, Long> {
    List<ComplementoGrupo> findByProdutoIdOrderByIdAsc(Long produtoId);
}
