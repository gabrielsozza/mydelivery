package com.mydelivery.equipe;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Registra ações auditáveis em background — nunca bloqueia o request principal.
 *
 * Design:
 *   - Fire-and-forget num pool dedicado. Falha ao gravar (banco down, etc)
 *     só loga, não propaga. Auditoria é "nice to have" — perder 1 evento
 *     é aceitável, quebrar checkout por causa de audit não é.
 *   - Ator (proprietário vs membro) resolvido do PermissaoContext atual.
 *     Se contexto for null (job de background, cron), aceita como "sistema".
 *   - IP capturado do HttpServletRequest via RequestContextHolder.
 *
 * Uso típico:
 *   auditoriaService.registrar(AcaoAuditoria.PEDIDO_CANCELADO, "Pedido",
 *                              String.valueOf(pedido.getId()), null);
 *
 * NÃO logue senhas, tokens, PIX, cartão em detalhesJson. Considere isso lei.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditoriaService {

    private final LogAuditoriaRepository repo;

    private static final ExecutorService POOL =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "auditoria-worker");
                t.setDaemon(true);
                return t;
            });

    /**
     * Registra ação. Assíncrono — retorna imediato.
     *
     * @param restauranteId tenant (não pode ser null; deriva do request se puder)
     * @param acao          o que aconteceu
     * @param entidadeTipo  ex "Produto", "Pedido" — pode ser null
     * @param entidadeId    id da entidade (aceita qualquer formato) — pode ser null
     * @param detalhesJson  JSON livre de contexto; NÃO PII sensível
     */
    public void registrar(Long restauranteId,
                          AcaoAuditoria acao,
                          String entidadeTipo,
                          String entidadeId,
                          String detalhesJson) {
        // Snapshot do ator + IP na thread do request — não pode ser lido no worker
        final PermissaoContext.Contexto ctx = PermissaoContext.atual();
        final String ip = capturarIp();
        final String atorLabel = resolverAtorLabel(ctx);
        final Long membroId = ctx == null ? null : ctx.membroId();

        POOL.submit(() -> {
            try {
                repo.save(LogAuditoria.builder()
                        .restauranteId(restauranteId)
                        .membroId(membroId)
                        .atorLabel(atorLabel)
                        .acao(acao)
                        .entidadeTipo(entidadeTipo)
                        .entidadeId(entidadeId)
                        .detalhesJson(detalhesJson)
                        .ip(ip)
                        .build());
            } catch (Exception e) {
                log.warn("[Auditoria] falha ao gravar {} pra rest={}: {}",
                        acao, restauranteId, e.getMessage());
            }
        });
    }

    private static String resolverAtorLabel(PermissaoContext.Contexto ctx) {
        if (ctx == null) return "Sistema";
        if (ctx.ehProprietario()) return "Proprietário";
        return (ctx.login() == null ? "?" : ctx.login())
                + " (" + (ctx.cargo() == null ? "?" : ctx.cargo().getLabel()) + ")";
    }

    private static String capturarIp() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            var req = attrs.getRequest();
            String h = req.getHeader("X-Forwarded-For");
            if (h != null && !h.isBlank()) return h.split(",")[0].trim();
            return req.getRemoteAddr();
        } catch (Exception e) { return null; }
    }
}
