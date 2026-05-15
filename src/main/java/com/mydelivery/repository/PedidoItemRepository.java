package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.PedidoItem;

public interface PedidoItemRepository extends JpaRepository<PedidoItem, Long> {

    /** Usado quando deletamos um produto — precisamos desvincular antes pra evitar FK violation. */
    List<PedidoItem> findByProdutoId(Long produtoId);
}
