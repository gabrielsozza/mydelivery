package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.Categoria;
import com.mydelivery.model.Produto;

public interface ProdutoRepository extends JpaRepository<Produto, Long> {
    Optional<Produto> findFirstByNome(String nome);
    List<Produto> findByRestauranteIdAndDisponivelTrue(Long restauranteId);
    List<Produto> findByRestauranteId(Long restauranteId);
    List<Produto> findByCategoriaId(Long categoriaId);
    List<Produto> findByCategoriaAndDisponivelTrueOrderByOrdem(Categoria categoria);
    List<Produto> findByCategoriaIdAndRestauranteIdOrderByOrdem(Long categoriaId, Long restauranteId);
}