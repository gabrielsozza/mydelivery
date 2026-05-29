package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.ComplementoItem;

public interface ComplementoItemRepository extends JpaRepository<ComplementoItem, Long> {
    List<ComplementoItem> findByGrupoId(Long grupoId);
}
