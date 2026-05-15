package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.Categoria;
import com.mydelivery.model.Restaurante;

public interface CategoriaRepository extends JpaRepository<Categoria, Long> {

    List<Categoria> findByRestauranteIdAndAtivoTrueOrderByOrdemAsc(Long restauranteId);

    List<Categoria> findByRestauranteIdOrderByOrdemAsc(Long restauranteId);

    List<Categoria> findByRestauranteAndAtivoTrueOrderByOrdem(Restaurante restaurante);
}