package com.mydelivery.equipe;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

/**
 * Aspect que valida @PermissaoRequerida antes de executar o método.
 *
 * Falha com 403 (Forbidden) — não 401 — porque o usuário está AUTENTICADO
 * mas não tem AUTORIZAÇÃO. Front usa 403 pra mostrar mensagem clara
 * ("Você não tem permissão pra essa ação").
 *
 * Semântica multi-permissão: OR (basta ter uma).
 *
 * Ordem: como o filter JWT já rodou antes de qualquer controller, o
 * PermissaoContext está preenchido. Se null (endpoint público mal
 * anotado por engano), tratamos como sem permissão — 403.
 */
@Slf4j
@Aspect
@Component
public class PermissaoAspect {

    @Around("@annotation(com.mydelivery.equipe.PermissaoRequerida)")
    public Object verificar(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        PermissaoRequerida ann = sig.getMethod().getAnnotation(PermissaoRequerida.class);
        Permissao[] requeridas = ann.value();

        PermissaoContext.Contexto ctx = PermissaoContext.atual();
        if (ctx == null) {
            log.warn("[Permissao] request sem contexto tentou {} — negado",
                    sig.getDeclaringType().getSimpleName() + "." + sig.getName());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem contexto de autenticação");
        }
        // Dono passa sempre. Log preservado pra rastrear alguém explorando
        // endpoints escondidos.
        if (ctx.ehProprietario()) {
            return pjp.proceed();
        }
        for (Permissao p : requeridas) {
            if (ctx.permissoes() != null && ctx.permissoes().contains(p)) {
                return pjp.proceed();
            }
        }
        log.warn("[Permissao] membro {} ({}) tentou {} sem permissão. Requeria: {}",
                ctx.membroId(), ctx.login(),
                sig.getDeclaringType().getSimpleName() + "." + sig.getName(),
                java.util.Arrays.toString(requeridas));
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Sem permissão pra realizar essa ação");
    }
}
