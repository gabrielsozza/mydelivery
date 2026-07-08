package com.mydelivery.equipe;

import java.util.EnumSet;
import java.util.Set;

/**
 * Contexto de autorização do request atual — resolvido pelo
 * JwtAuthenticationFilter e disponibilizado pra qualquer camada (controller,
 * service, aspect) via getters estáticos.
 *
 * Por que ThreadLocal ao invés de campos no SecurityContext:
 *   Podíamos guardar num Authentication custom, mas isso quebraria os
 *   @AuthenticationPrincipal String email que já estão em 30+ controllers.
 *   O padrão atual do sistema usa String como principal (email do dono).
 *   ThreadLocal complementa: SecurityContext mantém o email (compat total),
 *   PermissaoContext carrega o membroId/permissões separado.
 *
 * Limpeza:
 *   ThreadLocal.remove() é chamado no finally do filter — se esquecer,
 *   thread pool do Tomcat vaza contexto pro próximo request. Testar!
 */
public final class PermissaoContext {

    private PermissaoContext() {}

    /**
     * Estado carregado por request. Usa record — imutável, fácil de raciocinar.
     * membroId=null significa PROPRIETÁRIO (Usuario 1:1 com Restaurante).
     */
    public static record Contexto(
            Long membroId,
            String login,
            Cargo cargo,
            Set<Permissao> permissoes) {
        public boolean ehProprietario() { return membroId == null; }
    }

    private static final ThreadLocal<Contexto> CTX = new ThreadLocal<>();

    /** Chamado pelo filter no início do request. */
    public static void set(Contexto ctx) { CTX.set(ctx); }

    /** Chamado no finally do filter. NÃO ESQUECER. */
    public static void clear() { CTX.remove(); }

    /**
     * @return contexto do request atual. null se request não passou pelo
     *         filter autenticado (endpoint público, health check).
     */
    public static Contexto atual() { return CTX.get(); }

    /**
     * Verifica se o ator do request pode fazer a permissão dada.
     * Proprietário SEMPRE passa (super-user). Membro precisa ter a
     * permissão explicitamente no set.
     */
    public static boolean pode(Permissao permissao) {
        Contexto c = CTX.get();
        if (c == null) return false;           // não autenticado
        if (c.ehProprietario()) return true;   // dono passa em tudo
        return c.permissoes() != null && c.permissoes().contains(permissao);
    }

    /**
     * Set imutável de permissões efetivas (pra proprietário retorna tudo).
     * Usado no /auth/me pra o frontend mostrar/esconder itens.
     */
    public static Set<Permissao> permissoesEfetivas() {
        Contexto c = CTX.get();
        if (c == null) return Set.of();
        if (c.ehProprietario()) return EnumSet.allOf(Permissao.class);
        return c.permissoes() == null ? EnumSet.noneOf(Permissao.class) : c.permissoes();
    }
}
