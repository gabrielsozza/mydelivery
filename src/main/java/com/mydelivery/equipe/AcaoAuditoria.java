package com.mydelivery.equipe;

/**
 * Vocabulário fixo de ações auditadas. String livre daria trabalho de
 * filtro/relatório no futuro; enum garante conjunto conhecido.
 *
 * Adicionar caso novo é seguro (banco guarda o name como VARCHAR).
 * Remover é DELICADO — logs históricos ficariam com valor "desconhecido".
 * Se remover, marcar como @Deprecated primeiro.
 */
public enum AcaoAuditoria {
    LOGIN,
    LOGOUT,
    LOGIN_FALHOU,
    PEDIDO_CRIADO,
    PEDIDO_CANCELADO,
    PEDIDO_STATUS_ALTERADO,
    PEDIDO_EXCLUIDO,
    PRODUTO_CRIADO,
    PRODUTO_EDITADO,
    PRODUTO_EXCLUIDO,
    PRECO_ALTERADO,
    ESTOQUE_ALTERADO,
    CATEGORIA_CRIADA,
    CATEGORIA_EXCLUIDA,
    CONFIG_ALTERADA,
    MEMBRO_CRIADO,
    MEMBRO_EDITADO,
    MEMBRO_EXCLUIDO,
    MEMBRO_BLOQUEADO,
    MEMBRO_DESBLOQUEADO,
    DESCONTO_CONCEDIDO,
    RELATORIO_EMITIDO,
    FECHAMENTO_CAIXA
}
