package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mydelivery.model.PontosTransacao;

public interface PontosTransacaoRepository extends JpaRepository<PontosTransacao, Long> {

    List<PontosTransacao> findByRestauranteIdAndTelefoneClienteOrderByCriadoEmAsc(
            Long restauranteId, String telefoneCliente);

    /**
     * Soma pontos atualmente válidos do cliente (créditos não expirados − débitos).
     * Retorna Long (pode ser null se não houver registros) → tratar como 0 no service.
     */
    @Query("""
        SELECT COALESCE(SUM(
            CASE
                WHEN t.tipo = com.mydelivery.model.PontosTransacao.Tipo.CREDITO
                     AND (t.expiraEm IS NULL OR t.expiraEm > :agora) THEN t.pontos
                WHEN t.tipo = com.mydelivery.model.PontosTransacao.Tipo.DEBITO_RECOMPENSA THEN -t.pontos
                ELSE 0
            END
        ), 0)
        FROM PontosTransacao t
        WHERE t.restaurante.id = :restauranteId AND t.telefoneCliente = :telefone
        """)
    Long calcularSaldo(@Param("restauranteId") Long restauranteId,
                       @Param("telefone") String telefone,
                       @Param("agora") LocalDateTime agora);

    /**
     * Busca créditos a expirar — usado pelo job de expiração.
     */
    List<PontosTransacao> findByTipoAndExpiraEmBefore(PontosTransacao.Tipo tipo, LocalDateTime limite);
}
