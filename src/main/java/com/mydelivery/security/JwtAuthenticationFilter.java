package com.mydelivery.security;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mydelivery.equipe.MembroEquipe;
import com.mydelivery.equipe.MembroEquipeRepository;
import com.mydelivery.equipe.Permissao;
import com.mydelivery.equipe.PermissaoContext;
import com.mydelivery.equipe.StatusMembro;
import com.mydelivery.repository.RestauranteRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Autentica requests via JWT + preenche PermissaoContext quando o token
 * pertence a um membro da equipe.
 *
 * Fluxo:
 *   1. Sem token → passa (endpoints públicos e /login lidam).
 *   2. Token de DONO (sem claim membroId) → SecurityContext com email,
 *      PermissaoContext com contexto de proprietário (ehProprietario=true).
 *   3. Token de MEMBRO (claim membroId presente):
 *      - Busca tokenVersion no DB. Se != da claim → 401 (sessão invalidada
 *        por bloqueio/edição/exclusão do membro).
 *      - Se status == BLOQUEADO → 401 (defesa em profundidade — normalmente
 *        já teria bumped tokenVersion, mas backup).
 *      - Carrega permissões + set no PermissaoContext.
 *      - SecurityContext ainda recebe o email do dono (compat com controllers
 *        existentes que fazem @AuthenticationPrincipal String email).
 *   4. finally: PermissaoContext.clear() SEMPRE — impede vazamento pro
 *      próximo request na mesma thread do pool do Tomcat.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final RestauranteRepository restauranteRepository;
    private final MembroEquipeRepository membroRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.tokenValido(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        String email = jwtUtil.extrairEmail(token);
        String role  = jwtUtil.extrairRole(token);
        Long membroId = jwtUtil.extrairMembroId(token);

        try {
            // Autenticação básica no SecurityContext (não muda o padrão atual).
            if (SecurityContextHolder.getContext().getAuthentication() == null && role != null) {
                List<SimpleGrantedAuthority> authorities =
                        List.of(new SimpleGrantedAuthority("ROLE_" + role));
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(email, null, authorities);
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }

            // Contexto de permissão — proprietário ou membro
            if (membroId == null) {
                // JWT de dono. Sem membroId, contexto ehProprietario=true.
                PermissaoContext.set(new PermissaoContext.Contexto(
                        null, email, com.mydelivery.equipe.Cargo.PROPRIETARIO,
                        EnumSet.allOf(Permissao.class)));
            } else {
                MembroEquipe m = membroRepository.findById(membroId).orElse(null);
                if (m == null) {
                    log.warn("[JwtFilter] token pra membroId={} que nao existe mais", membroId);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                int versionNoToken = jwtUtil.extrairTokenVersion(token);
                if (versionNoToken != (m.getTokenVersion() == null ? 0 : m.getTokenVersion())) {
                    log.info("[JwtFilter] tokenVersion invalido pra membroId={} (bloqueio/edicao aplicado)", membroId);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                if (m.getStatus() != StatusMembro.ATIVO) {
                    log.info("[JwtFilter] membro {} bloqueado — negando", membroId);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                Set<Permissao> permissoes = parsePermissoes(m.getPermissoesCsv());
                PermissaoContext.set(new PermissaoContext.Contexto(
                        m.getId(), m.getLogin(), m.getCargo(), permissoes));
            }

            filterChain.doFilter(request, response);
        } finally {
            PermissaoContext.clear();
        }
    }

    /**
     * Parse CSV → EnumSet<Permissao>. Enums desconhecidos (removidos numa
     * migração futura) são ignorados em silêncio — não quebra o request.
     */
    private static Set<Permissao> parsePermissoes(String csv) {
        Set<Permissao> out = EnumSet.noneOf(Permissao.class);
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) {
            String norm = s.trim();
            if (norm.isEmpty()) continue;
            try {
                out.add(Permissao.valueOf(norm));
            } catch (IllegalArgumentException ignore) {
                // enum removida — ignora
            }
        }
        return out;
    }
}
