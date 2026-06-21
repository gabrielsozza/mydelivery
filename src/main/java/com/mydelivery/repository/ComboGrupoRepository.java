package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mydelivery.model.ComboGrupo;

public interface ComboGrupoRepository extends JpaRepository<ComboGrupo, Long> {

    /** Lista os grupos de um combo, ordenados pra apresentação. */
    List<ComboGrupo> findByComboIdOrderByOrdemAscIdAsc(Long comboId);

    /** Remove todas as ligações de um combo (usado em UPDATE/DELETE). */
    @Modifying
    @Query("delete from ComboGrupo cg where cg.combo.id = :comboId")
    void deleteByComboId(@Param("comboId") Long comboId);
}
