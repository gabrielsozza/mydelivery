package com.mydelivery.repository;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.mydelivery.model.PromoCampanhaEnvio;

public interface PromoCampanhaEnvioRepository extends JpaRepository<PromoCampanhaEnvio, Long> {

    /** Usa o contador da ficha de clientes: recorrentes, uma compra, zero compras.
     *  MAX evita multiplicar prioridade por cadastros duplicados do mesmo telefone.
     *  Só pendentes são ordenados; histórico e IDs da fila permanecem intactos. */
    String FILA_PRIORITARIA_SQL = """
        SELECT e.* FROM promo_campanha_envios e
        JOIN promo_campanhas campanha ON campanha.id=e.campanha_id
        LEFT JOIN clientes c ON c.restaurante_id=campanha.restaurante_id AND (
            REGEXP_REPLACE(c.telefone, '[^0-9]', '')=e.telefone
            OR CONCAT('55',REGEXP_REPLACE(c.telefone, '[^0-9]', ''))=e.telefone
            OR REGEXP_REPLACE(c.telefone, '[^0-9]', '')=CONCAT('55',e.telefone))
        WHERE e.campanha_id=:campanhaId AND e.enviado_em IS NULL
        GROUP BY e.id
        ORDER BY MAX(COALESCE(c.total_pedidos,0)) DESC,e.id ASC
        """;
    @Query(value=FILA_PRIORITARIA_SQL, nativeQuery=true)
    List<PromoCampanhaEnvio> findPendentes(Long campanhaId, PageRequest limit);

    /** Limites persistentes: incluem todas as campanhas da loja e sobrevivem a reinícios. */
    @Query("SELECT COUNT(e) FROM PromoCampanhaEnvio e WHERE e.campanha.restaurante.id = :restauranteId AND e.sucesso = true AND e.enviadoEm >= :desde")
    long countEnviadosDesde(Long restauranteId, java.time.LocalDateTime desde);

    int countByCampanhaId(Long campanhaId);
    int countByCampanhaIdAndSucessoTrue(Long campanhaId);
    int countByCampanhaIdAndSucessoFalse(Long campanhaId);
    int countByCampanhaIdAndEnviadoEmIsNull(Long campanhaId);
}
