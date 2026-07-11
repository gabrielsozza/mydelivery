package com.mydelivery.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mydelivery.model.MovimentacaoCaixa;

public interface MovimentacaoCaixaRepository extends JpaRepository<MovimentacaoCaixa, Long> {

    List<MovimentacaoCaixa> findByCaixaIdOrderByCriadoEmAsc(Long caixaId);

    /**
     * Retorna a soma dos valores por tipo pra um caixa. Usado no resumo de
     * fechamento — evita carregar toda a lista de movimentações em memória
     * quando o caixa tem muitas vendas.
     */
    @Query("""
        SELECT m.tipo, COALESCE(SUM(m.valor), 0) FROM MovimentacaoCaixa m
        WHERE m.caixa.id = :caixaId
        GROUP BY m.tipo
    """)
    List<Object[]> somasPorTipo(@Param("caixaId") Long caixaId);

    /**
     * Guarda idempotência: verifica se já existe VENDA_* pra esse pedido no
     * caixa. Evita duplicar movimentação em caso de retry ou reprocessamento.
     */
    @Query("""
        SELECT COUNT(m) FROM MovimentacaoCaixa m
        WHERE m.caixa.id = :caixaId
          AND m.pedidoId = :pedidoId
          AND m.tipo IN (
              com.mydelivery.model.MovimentacaoCaixa.Tipo.VENDA_DINHEIRO,
              com.mydelivery.model.MovimentacaoCaixa.Tipo.VENDA_PIX,
              com.mydelivery.model.MovimentacaoCaixa.Tipo.VENDA_CREDITO,
              com.mydelivery.model.MovimentacaoCaixa.Tipo.VENDA_DEBITO
          )
    """)
    long contarVendasPorPedido(@Param("caixaId") Long caixaId,
                                @Param("pedidoId") Long pedidoId);

    /**
     * Soma total do caixa por tipo genérico (dinheiro vs cartão vs pix).
     * Usado no dashboard e no fechamento pra montar as linhas do resumo.
     */
    default BigDecimal somarValor(Long caixaId, MovimentacaoCaixa.Tipo tipo) {
        return findByCaixaIdOrderByCriadoEmAsc(caixaId).stream()
                .filter(m -> m.getTipo() == tipo)
                .map(MovimentacaoCaixa::getValor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
