package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mydelivery.model.Banner;

public interface BannerRepository extends JpaRepository<Banner, Long> {

    List<Banner> findByRestauranteIdOrderByOrdemAsc(Long restauranteId);

    List<Banner> findByRestauranteIdAndAtivoTrueOrderByOrdemAsc(Long restauranteId);

    /** Limpa vínculo de produto em todos os banners (usado no delete do produto). */
    @Modifying
    @Query("UPDATE Banner b SET b.produto = null WHERE b.produto.id = :produtoId")
    int desvincularProduto(@Param("produtoId") Long produtoId);
}
