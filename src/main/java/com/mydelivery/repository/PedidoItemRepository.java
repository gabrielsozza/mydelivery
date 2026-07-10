package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.PedidoItem;

public interface PedidoItemRepository extends JpaRepository<PedidoItem, Long> {

    /** Usado quando deletamos um produto — precisamos desvincular antes pra evitar FK violation. */
    List<PedidoItem> findByProdutoId(Long produtoId);

    /**
     * Itens do pedido pra reconstruir o carrinho no "Pedir novamente".
     * LEFT JOIN FETCH em produto pra pegar preço atual + disponibilidade
     * numa consulta só — evita N+1 e mantém latência do endpoint sub-50ms.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT i FROM PedidoItem i LEFT JOIN FETCH i.produto WHERE i.pedido.id = :pedidoId ORDER BY i.id ASC")
    List<PedidoItem> findByPedidoIdComProduto(@org.springframework.data.repository.query.Param("pedidoId") Long pedidoId);
}
