package com.mydelivery.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.mydelivery.model.Pedido;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pedido p where p.id = :id")
    java.util.Optional<Pedido> findByIdForFiscal(@org.springframework.data.repository.query.Param("id") Long id);

    boolean existsByIdAndRestauranteId(Long id, Long restauranteId);

    List<Pedido> findByRestauranteIdOrderByCriadoEmDesc(Long restauranteId);

    List<Pedido> findByRestauranteIdAndStatusOrderByCriadoEmDesc(
            Long restauranteId, Pedido.Status status);

    // Hibernate não permite buscar duas listas (bags) no mesmo JOIN FETCH.
    // Os complementos são carregados sob demanda dentro da transação do fluxo.
    @Query("select distinct p from Pedido p left join fetch p.itens "
            + "where p.restaurante.id = :restauranteId and p.cliente.telefone in :telefones "
            + "and p.status in :status and p.tipo in :tipos order by p.criadoEm desc")
    List<Pedido> findPedidosRepetiveis(@org.springframework.data.repository.query.Param("restauranteId") Long restauranteId,
            @org.springframework.data.repository.query.Param("telefones") java.util.Collection<String> telefones,
            @org.springframework.data.repository.query.Param("status") java.util.Collection<Pedido.Status> status,
            @org.springframework.data.repository.query.Param("tipos") java.util.Collection<Pedido.Tipo> tipos);

    @Query("SELECT p FROM Pedido p WHERE p.restaurante.id = :restauranteId " +
           "AND p.criadoEm BETWEEN :inicio AND :fim ORDER BY p.criadoEm DESC")
    List<Pedido> findByRestauranteIdAndPeriodo(
            Long restauranteId, LocalDateTime inicio, LocalDateTime fim);

    /**
     * Comanda da mesa (todos os pedidos ATIVOS — não cancelados/entregues —
     * dessa mesa). Usado tanto pelo cliente final (visão própria filtrada
     * por nome) quanto pelo painel (visão geral por mesa).
     */
    @Query("SELECT p FROM Pedido p WHERE p.mesa.id = :mesaId " +
           "AND p.status NOT IN ('CANCELADO', 'ENTREGUE') ORDER BY p.criadoEm ASC")
    List<Pedido> findComandaAtivaPorMesa(Long mesaId);

    /** Todos os pedidos de uma sessão de mesa específica (Garçom). */
    List<Pedido> findBySessaoIdOrderByCriadoEmAsc(Long sessaoId);

    /** Idempotência da integração iFood — evita criar duplicata caso o
     *  polling pegue o mesmo PLC event 2x (acontece se ACK falhar). */
    java.util.Optional<Pedido> findByIfoodOrderId(String ifoodOrderId);

    java.util.Optional<Pedido> findByWhatsappRepeticaoConversaId(Long conversaId);

    @Query("select p from Pedido p where p.rastreamentoTokenHash = :tokenHash or p.rastreamentoTokenAnteriorHash = :tokenHash")
    java.util.Optional<Pedido> findByRastreamentoToken(String tokenHash);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pedido p where p.id = :id")
    java.util.Optional<Pedido> findByIdForTracking(@org.springframework.data.repository.query.Param("id") Long id);

    @Query("select p from Pedido p where p.restaurante.id = :restauranteId and p.status = com.mydelivery.model.Pedido.Status.SAIU_ENTREGA and p.rastreamentoConsentidoEm is not null")
    List<Pedido> findRastreamentosAtivosPorRestaurante(@org.springframework.data.repository.query.Param("restauranteId") Long restauranteId);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE pedidos SET rastreamento_token_hash=NULL, rastreamento_token_anterior_hash=NULL, rastreamento_consentido_em=NULL, destino_latitude=NULL, destino_longitude=NULL, entregador_latitude=NULL, entregador_longitude=NULL, entregador_localizacao_em=NULL, rastreamento_expira_em=NULL WHERE rastreamento_expira_em IS NOT NULL AND rastreamento_expira_em < :agora", nativeQuery = true)
    int limparLocalizacaoExpirada(@org.springframework.data.repository.query.Param("agora") LocalDateTime agora);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE pedidos SET entrega_codigo_hash=NULL, entrega_codigo_expira_em=NULL, entrega_codigo_tentativas=0 WHERE entrega_codigo_expira_em IS NOT NULL AND entrega_codigo_expira_em < :agora", nativeQuery = true)
    int limparCodigosExpirados(@org.springframework.data.repository.query.Param("agora") LocalDateTime agora);

    /**
     * Pedidos esquecidos em SAIU_ENTREGA — restaurante saiu pra entregar e
     * não voltou pra marcar ENTREGUE. Job auto-fecha após 2h30min de
     * inatividade (parametrizável).
     *
     * Só DELIVERY/RETIRADA — mesa/balcão têm fluxo próprio.
     * Só status SAIU_ENTREGA — é a única transição em que "esqueceu de
     * marcar" faz sentido virar ENTREGUE automaticamente.
     */
    @Query("SELECT p FROM Pedido p " +
           "WHERE p.status = com.mydelivery.model.Pedido.Status.SAIU_ENTREGA " +
           "  AND p.tipo IN (com.mydelivery.model.Pedido.Tipo.DELIVERY, com.mydelivery.model.Pedido.Tipo.RETIRADA) " +
           "  AND p.atualizadoEm < :limite")
    List<Pedido> findEsquecidosParaEntregaAutomatica(LocalDateTime limite);

    /**
     * Último pedido NÃO-finalizado de um cliente identificado pelo telefone,
     * dentro do restaurante específico. Usado pelo bot WhatsApp pra responder
     * "cadê meu pedido" sem precisar transferir pra atendente.
     *
     * Filtra:
     *  - Restaurante específico (multi-tenant)
     *  - Telefone do cliente (limpo de máscara)
     *  - Status ATIVO (não ENTREGUE/CANCELADO finais — mas se cliente perguntar
     *    logo após entregar, ainda mostramos pelo período curto)
     *  - Últimas 24h (pra não confundir com pedido antigo do mesmo cliente)
     *  - Ordena por mais recente primeiro
     */
    @Query("SELECT p FROM Pedido p " +
           "WHERE p.restaurante.id = :restauranteId " +
           "  AND p.cliente.telefone = :telefone " +
           "  AND p.criadoEm >= :desde " +
           "ORDER BY p.criadoEm DESC")
    List<Pedido> findUltimosDoTelefone(Long restauranteId, String telefone,
                                       LocalDateTime desde);

    /**
     * Batch lookup nome cliente por telefone: retorna [telefoneNormalizado, nomeCliente]
     * do PEDIDO MAIS RECENTE de cada telefone da lista, dentro do restaurante.
     * Usado pra enriquecer cupons FIDELIDADE com o nome do ganhador (antes
     * aparecia só "Cliente" no painel do dono). Nome vem de p.nomeCliente ou
     * p.cliente.nome — preferimos nomeCliente porque bate com o que dono viu
     * no card do pedido.
     */
    @Query("SELECT c.telefone, c.nome " +
           "FROM Pedido p JOIN p.cliente c " +
           "WHERE p.restaurante.id = :restauranteId " +
           "  AND c.telefone IN :telefones " +
           "  AND c.nome IS NOT NULL " +
           "ORDER BY p.criadoEm DESC")
    List<Object[]> findNomesPorTelefones(Long restauranteId, java.util.Collection<String> telefones);

    /** Zera a FK mesa em pedidos históricos — usado no DELETE de mesa
     *  pra permitir excluir sem violar constraint. Pedido continua com
     *  nome_cliente_mesa preenchido (histórico financeiro preservado). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE Pedido p SET p.mesa = null WHERE p.mesa.id = :mesaId")
    void desvincularMesa(@org.springframework.data.repository.query.Param("mesaId") Long mesaId);

    /**
     * Clientes elegíveis pra campanha promocional — retorna [telefone, nome, qtdPedidos]
     * de clientes com &gt;= minCompras pedidos NÃO cancelados nesse restaurante.
     * Ignora pedidos onde o telefone é nulo/vazio e pedidos de mesa/sessão sem cliente
     * associado. Usado pelo PromoCampanhaService pra popular a fila de disparo.
     */
    @Query("SELECT c.telefone, MAX(c.nome), COUNT(p) " +
           "FROM Pedido p JOIN p.cliente c " +
           "WHERE p.restaurante.id = :restauranteId " +
           "  AND c.telefone IS NOT NULL AND c.telefone <> '' " +
           "  AND p.status <> com.mydelivery.model.Pedido$Status.CANCELADO " +
           "GROUP BY c.telefone " +
           "HAVING COUNT(p) >= :minCompras")
    List<Object[]> findClientesRecorrentes(Long restauranteId, long minCompras);
}
