package com.mydelivery.equipe;

/**
 * Cargos padrão do sistema. É apenas um MODELO/ETIQUETA — o controle real
 * de acesso é feito pelo set de {@link Permissao} do membro.
 *
 *   PROPRIETARIO — dono do restaurante. Único que passa em toda
 *                  @PermissaoRequerida sem checagem. Sempre 1 por
 *                  restaurante (o Usuario original). NÃO tem entrada
 *                  na tabela membros_equipe.
 *   GERENTE      — cargo de confiança; template = tudo menos config
 *                  global + gerenciar usuários da equipe.
 *   FUNCIONARIO  — operacional; template = pedidos + cardápio (leitura)
 *                  + clientes.
 *
 * Novos cargos podem entrar aqui sem mudar mais nada — só definir o
 * template default em {@link Permissao#defaultDoCargo}.
 */
public enum Cargo {
    PROPRIETARIO,
    GERENTE,
    FUNCIONARIO,
    /** Contador: acesso completo à Área Fiscal (config, categorias tributárias,
     *  contador NFC-e, entradas de fornecedor, relatórios, emitir/cancelar).
     *  Sem acesso a operação da loja (pedidos, cardápio, config geral). */
    CONTADOR;

    public String getLabel() {
        switch (this) {
            case PROPRIETARIO: return "Proprietário";
            case GERENTE: return "Gerente";
            case FUNCIONARIO: return "Funcionário";
            case CONTADOR: return "Contador";
            default: return name();
        }
    }
}
