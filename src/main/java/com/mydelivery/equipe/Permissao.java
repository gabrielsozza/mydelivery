package com.mydelivery.equipe;

import java.util.EnumSet;
import java.util.Set;

/**
 * Catálogo central de permissões do sistema. Duas famílias:
 *
 *  1. VER_*  — permissões de VISUALIZAÇÃO (aparecer no menu / abrir a tela).
 *              O front usa pra montar sidebar + guard de rota. Backend usa
 *              pra proteger endpoints de leitura sensíveis.
 *
 *  2. Verbos — permissões de AÇÃO específica ("CANCELAR_PEDIDOS",
 *              "EXCLUIR_PRODUTOS", etc). Backend usa via @PermissaoRequerida
 *              pra bloquear a ação mesmo pra quem consegue ver a tela.
 *
 * ADIÇÃO DE NOVA PERMISSÃO:
 *   1. Coloca uma entrada nova aqui
 *   2. Aplica ela no template do Cargo apropriado em defaultDoCargo()
 *   3. Front detecta automático via /api/auth/permissoes-catalogo
 *
 * IMPORTANTE:
 *   - Proprietário SEMPRE passa em qualquer @PermissaoRequerida — não precisa
 *     estar no set dele. O aspect trata isso como caso especial.
 *   - Enum é a fonte da verdade. String no banco (permissoes_json) armazena
 *     só nomes de enum válidos; enums removidas são ignoradas silenciosamente
 *     ao ler, evitando quebra em migrações.
 */
public enum Permissao {
    // ── Visualização de módulos (menu lateral + acesso à tela) ─────────
    VER_DASHBOARD,
    VER_PEDIDOS_DELIVERY,
    VER_PEDIDOS_MESA,
    VER_PEDIDOS_BALCAO,
    VER_CARDAPIO,
    VER_PRODUTOS,
    VER_CATEGORIAS,
    VER_COMBOS,
    VER_COMPLEMENTOS,
    VER_BANNER,
    VER_ESTOQUE,
    VER_COMPRAS,
    VER_FICHA_TECNICA,
    VER_FINANCEIRO,
    VER_FIDELIDADE,
    VER_CLIENTES,
    VER_ENTREGADORES,
    VER_GARCOMS,
    VER_EQUIPE,
    VER_CONFIGURACOES,
    VER_ASSISTENTE,
    VER_RELATORIOS,
    VER_CUPONS,
    VER_PROMOCOES,
    VER_IMPRESSORAS,
    VER_INTEGRACOES,

    // ── Ações específicas (backend protege via @PermissaoRequerida) ────
    CANCELAR_PEDIDOS,
    ALTERAR_STATUS_PEDIDOS,
    CONCEDER_DESCONTO,
    EDITAR_PRECOS,
    EXCLUIR_PRODUTOS,
    EXCLUIR_CATEGORIAS,
    EXCLUIR_PEDIDOS,
    EDITAR_ESTOQUE,
    FECHAMENTO_CAIXA,
    EMITIR_RELATORIOS,
    ALTERAR_CONFIGURACOES,
    CADASTRAR_USUARIOS,
    EDITAR_USUARIOS,
    EXCLUIR_USUARIOS;

    /**
     * Grupo lógico da permissão (usado pelo front pra agrupar checkboxes
     * no modal de criar/editar membro).
     */
    public String getGrupo() {
        String n = name();
        if (n.startsWith("VER_")) return "Módulos";
        if (n.startsWith("EXCLUIR_")) return "Exclusões";
        if (n.startsWith("EDITAR_")) return "Edição";
        return "Ações";
    }

    /**
     * Rótulo amigável mostrado ao dono. Enum é a chave; label é UX.
     */
    public String getLabel() {
        switch (this) {
            // Módulos
            case VER_DASHBOARD: return "Dashboard";
            case VER_PEDIDOS_DELIVERY: return "Pedidos Delivery";
            case VER_PEDIDOS_MESA: return "Pedidos Mesa";
            case VER_PEDIDOS_BALCAO: return "Pedidos Balcão";
            case VER_CARDAPIO: return "Cardápio";
            case VER_PRODUTOS: return "Produtos";
            case VER_CATEGORIAS: return "Categorias";
            case VER_COMBOS: return "Combos";
            case VER_COMPLEMENTOS: return "Complementos";
            case VER_BANNER: return "Banner";
            case VER_ESTOQUE: return "Estoque";
            case VER_COMPRAS: return "Compras";
            case VER_FICHA_TECNICA: return "Ficha Técnica";
            case VER_FINANCEIRO: return "Financeiro";
            case VER_FIDELIDADE: return "Fidelidade";
            case VER_CLIENTES: return "Clientes";
            case VER_ENTREGADORES: return "Entregadores";
            case VER_GARCOMS: return "Garçons";
            case VER_EQUIPE: return "Equipe";
            case VER_CONFIGURACOES: return "Configurações";
            case VER_ASSISTENTE: return "Assistente";
            case VER_RELATORIOS: return "Relatórios";
            case VER_CUPONS: return "Cupons";
            case VER_PROMOCOES: return "Promoções";
            case VER_IMPRESSORAS: return "Impressoras";
            case VER_INTEGRACOES: return "Integrações";
            // Ações
            case CANCELAR_PEDIDOS: return "Cancelar pedidos";
            case ALTERAR_STATUS_PEDIDOS: return "Alterar status dos pedidos";
            case CONCEDER_DESCONTO: return "Conceder desconto";
            case EDITAR_PRECOS: return "Editar preços";
            case EXCLUIR_PRODUTOS: return "Excluir produtos";
            case EXCLUIR_CATEGORIAS: return "Excluir categorias";
            case EXCLUIR_PEDIDOS: return "Excluir pedidos";
            case EDITAR_ESTOQUE: return "Editar estoque";
            case FECHAMENTO_CAIXA: return "Realizar fechamento de caixa";
            case EMITIR_RELATORIOS: return "Emitir relatórios";
            case ALTERAR_CONFIGURACOES: return "Alterar configurações da loja";
            case CADASTRAR_USUARIOS: return "Cadastrar novos usuários";
            case EDITAR_USUARIOS: return "Editar usuários";
            case EXCLUIR_USUARIOS: return "Excluir usuários";
            default: return name();
        }
    }

    /**
     * Set default de permissões pra cada cargo. Aplicado ao criar novo
     * membro — dono pode ajustar em cima. Retornar EnumSet (imutável)
     * evita mutação acidental do template.
     */
    public static Set<Permissao> defaultDoCargo(Cargo cargo) {
        switch (cargo) {
            case PROPRIETARIO:
                return EnumSet.allOf(Permissao.class);
            case GERENTE:
                // Vê tudo, faz quase tudo. Não mexe em usuários da equipe
                // (só dono cria/edita/exclui) e não altera configurações
                // globais da loja (evita gerente meter mão em MP/iFood).
                Set<Permissao> gerente = EnumSet.allOf(Permissao.class);
                gerente.remove(CADASTRAR_USUARIOS);
                gerente.remove(EDITAR_USUARIOS);
                gerente.remove(EXCLUIR_USUARIOS);
                gerente.remove(ALTERAR_CONFIGURACOES);
                return gerente;
            case FUNCIONARIO:
            default:
                // Só operacional: vê e mexe em pedidos, cardápio (leitura),
                // clientes. Sem acesso a financeiro, config, equipe, exclusões.
                return EnumSet.of(
                        VER_DASHBOARD,
                        VER_PEDIDOS_DELIVERY,
                        VER_PEDIDOS_MESA,
                        VER_PEDIDOS_BALCAO,
                        VER_CARDAPIO,
                        VER_PRODUTOS,
                        VER_CLIENTES,
                        ALTERAR_STATUS_PEDIDOS
                );
        }
    }
}
