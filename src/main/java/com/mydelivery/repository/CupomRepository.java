package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mydelivery.model.Cupom;

public interface CupomRepository extends JpaRepository<Cupom, Long> {

    Optional<Cupom> findByCodigoIgnoreCaseAndRestauranteId(String codigo, Long restauranteId);

    @Query("select c from Cupom c where upper(c.codigo)=upper(:codigo) and c.restaurante.slug=:slug and c.excluidoEm is null")
    Optional<Cupom> findByCodigoIgnoreCaseAndRestauranteSlug(@Param("codigo") String codigo, @Param("slug") String slug);

    @Query("select c from Cupom c where c.restaurante.id=:restauranteId and c.excluidoEm is null order by c.criadoEm desc")
    List<Cupom> findByRestauranteIdOrderByCriadoEmDesc(@Param("restauranteId") Long restauranteId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cupom c where c.id=:id")
    Optional<Cupom> findByIdForUpdate(@Param("id") Long id);

    /**
     * Lista cupons visíveis para o cliente final (no banner do cardápio público):
     * - ativos
     * - dentro da validade (ou sem validade)
     * - origem MANUAL (não mostra cupons gerados por fidelidade — esses são individuais)
     */
    @Query("""
        SELECT c FROM Cupom c
        WHERE c.restaurante.slug = :slug
          AND c.ativo = true
          AND c.excluidoEm IS NULL
          AND c.origem = com.mydelivery.model.Cupom.Origem.MANUAL
          AND (c.validadeInicio IS NULL OR c.validadeInicio <= :agora)
          AND (c.validadeFim IS NULL OR c.validadeFim >= :agora)
        ORDER BY c.criadoEm DESC
        """)
    List<Cupom> listarCuponsPublicos(@Param("slug") String slug, @Param("agora") LocalDateTime agora);
}
