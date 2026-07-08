package com.mydelivery.equipe;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um endpoint (ou service) como exigindo uma permissão específica
 * pra ser executado. O {@link PermissaoAspect} intercepta e valida contra
 * o {@link PermissaoContext} do request.
 *
 * Regra:
 *   - Proprietário passa em qualquer @PermissaoRequerida (super-user).
 *   - Membro só passa se {@link PermissaoContext#pode(Permissao)} retornar true.
 *   - Sem contexto (endpoint público não filtrado) → 403.
 *
 * Uso:
 *   @DeleteMapping("/api/produtos/{id}")
 *   @PermissaoRequerida(Permissao.EXCLUIR_PRODUTOS)
 *   public ResponseEntity<?> excluir(...) { ... }
 *
 * Múltiplas permissões (semântica OR — passa se tiver qualquer uma):
 *   @PermissaoRequerida({Permissao.VER_FINANCEIRO, Permissao.EMITIR_RELATORIOS})
 *
 * Vale só em métodos de bean gerenciado (controller/service com @Component).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PermissaoRequerida {
    Permissao[] value();
}
