package com.mydelivery.security;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Rate-limit simples por IP nos endpoints de login.
 *
 * <p>Sem isso, qualquer um podia fazer brute-force em /api/auth/login
 * e /api/garcom/&#42;/login. Agora limitamos 10 tentativas / 5 minutos
 * por IP. Excedeu = HTTP 429 com {@code Retry-After}.
 *
 * <p>Implementação in-memory (ConcurrentHashMap) — adequado pra 1
 * container. Quando escalar pra N pods, migrar pra Redis (Bucket4j +
 * Redisson). Reset automático: limpa entradas expiradas em cada acesso.
 *
 * <p>NÃO conta sucesso: se o login deu certo, zera o contador (não
 * pune usuário que digitou senha errada uma vez e acertou).
 */
@Slf4j
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TENTATIVAS = 10;
    private static final long JANELA_SEGUNDOS = 300; // 5 min

    private static final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static class Bucket {
        AtomicInteger contador = new AtomicInteger(0);
        volatile Instant resetEm = Instant.now().plusSeconds(JANELA_SEGUNDOS);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Aplica apenas em endpoints de autenticacao.
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;
        return !(path.equals("/api/auth/login")
                || path.equals("/auth/login")
                || (path.startsWith("/api/garcom/") && path.endsWith("/login")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String ip = ipDe(req);
        Bucket b = buckets.computeIfAbsent(ip, k -> new Bucket());

        // Reset se a janela expirou.
        if (Instant.now().isAfter(b.resetEm)) {
            b.contador.set(0);
            b.resetEm = Instant.now().plusSeconds(JANELA_SEGUNDOS);
        }

        int atual = b.contador.incrementAndGet();
        if (atual > MAX_TENTATIVAS) {
            long segundosFalta = Math.max(1, b.resetEm.getEpochSecond() - Instant.now().getEpochSecond());
            log.warn("[RateLimit] login bloqueado: ip={} tentativas={} reset_em_seg={}",
                    ip, atual, segundosFalta);
            res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            res.setHeader("Retry-After", String.valueOf(segundosFalta));
            res.setContentType("application/json");
            res.getWriter().write("{\"erro\":\"Muitas tentativas. Tente novamente em "
                    + (segundosFalta / 60 + 1) + " minutos.\"}");
            return;
        }

        chain.doFilter(req, res);

        // Login bem-sucedido (2xx) -> zera o contador pra esse IP.
        if (res.getStatus() >= 200 && res.getStatus() < 300) {
            b.contador.set(0);
        }
    }

    /** Pega o IP real considerando proxy reverso (Railway esta atras de proxy). */
    private String ipDe(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For: cliente, proxy1, proxy2 — pegamos o primeiro.
            int virg = xff.indexOf(',');
            return (virg > 0 ? xff.substring(0, virg) : xff).trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return req.getRemoteAddr();
    }

    /**
     * Limpeza periódica de buckets de IPs ja expirados pra evitar acumulo
     * de memoria. Roda a cada hora.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 60L * 60_000L)
    public void purgar() {
        Instant agora = Instant.now();
        int antes = buckets.size();
        buckets.entrySet().removeIf(e -> agora.isAfter(e.getValue().resetEm));
        int depois = buckets.size();
        if (antes != depois) {
            log.debug("[RateLimit] purga: {} -> {} buckets", antes, depois);
        }
    }
}
