package com.mydelivery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mydelivery.model.MyHelpMemoria;

public interface MyHelpMemoriaRepository extends JpaRepository<MyHelpMemoria, Long> {

    /** Memórias ATIVAS de um contexto: as da loja + as globais (restauranteId null). */
    @Query("select m from MyHelpMemoria m where m.ativa = true and m.contexto = :ctx "
            + "and (m.restauranteId = :loja or m.restauranteId is null) order by m.confianca desc")
    List<MyHelpMemoria> ativasDoContexto(@Param("loja") Long loja, @Param("ctx") String ctx);

    /** Chave exata de uma loja (pra upsert de aprendizado). Não inclui global. */
    Optional<MyHelpMemoria> findByRestauranteIdAndContextoAndChaveNorm(Long restauranteId, String contexto, String chaveNorm);

    /** Todas as memórias de uma loja — pra tela futura "Aprendizados do myHelp". */
    List<MyHelpMemoria> findByRestauranteIdOrderByAtualizadoEmDesc(Long restauranteId);
}
