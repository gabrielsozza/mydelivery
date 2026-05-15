package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.FichaTecnicaItem;

public interface FichaTecnicaItemRepository extends JpaRepository<FichaTecnicaItem, Long> {

    List<FichaTecnicaItem> findByProdutoId(Long produtoId);

    void deleteByProdutoId(Long produtoId);

    /** Todas as fichas de um restaurante — usado pra calcular viabilidade em lote. */
    List<FichaTecnicaItem> findByProdutoRestauranteId(Long restauranteId);
}
