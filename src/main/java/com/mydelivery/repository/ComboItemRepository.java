package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mydelivery.model.ComboItem;

public interface ComboItemRepository extends JpaRepository<ComboItem, Long> {

    /** Lista filhos de um combo, ordenados pra apresentação. */
    List<ComboItem> findByComboIdOrderByOrdemAscIdAsc(Long comboId);

    /** Remove todos os filhos de um combo (usado em UPDATE/DELETE do combo). */
    @Modifying
    @Query("delete from ComboItem ci where ci.combo.id = :comboId")
    void deleteByComboId(@Param("comboId") Long comboId);

    /** Remove todas as ligações onde o produto é usado como filho. Permite
     *  deletar um produto NORMAL que esteja sendo referenciado por algum
     *  combo (em vez de bloquear com FK constraint). */
    @Modifying
    @Query("delete from ComboItem ci where ci.produtoFilho.id = :produtoFilhoId")
    void deleteByProdutoFilhoId(@Param("produtoFilhoId") Long produtoFilhoId);

    /** Verifica se um produto está sendo usado como filho em algum combo
     *  do mesmo restaurante. Usado pra bloquear delete acidental. */
    @Query("select count(ci) from ComboItem ci where ci.produtoFilho.id = :produtoFilhoId")
    long countByProdutoFilhoId(@Param("produtoFilhoId") Long produtoFilhoId);
}
