package com.mydelivery.fiscal.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.mydelivery.fiscal.model.NotaEntradaAnexo;

public interface NotaEntradaAnexoRepository extends JpaRepository<NotaEntradaAnexo, Long> {
    interface Resumo {
        Long getId();
        String getNomeArquivo();
        String getTipoMime();
        Long getTamanho();
        LocalDateTime getCriadoEm();
    }

    Optional<NotaEntradaAnexo> findByRestauranteIdAndSha256(Long restauranteId, String sha256);

    Optional<NotaEntradaAnexo> findByIdAndRestauranteId(Long id, Long restauranteId);

    @Query("select a.id as id, a.nomeArquivo as nomeArquivo, a.tipoMime as tipoMime, "
            + "a.tamanho as tamanho, a.criadoEm as criadoEm from NotaEntradaAnexo a "
            + "where a.restaurante.id = :restauranteId order by a.criadoEm desc")
    List<Resumo> listarResumos(Long restauranteId);

    List<NotaEntradaAnexo> findByRestauranteIdAndCriadoEmBetween(
            Long restauranteId, LocalDateTime inicio, LocalDateTime fim);
}
