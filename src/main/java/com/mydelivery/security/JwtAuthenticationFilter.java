package com.mydelivery.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mydelivery.repository.RestauranteRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final RestauranteRepository restauranteRepository;

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

        // ⚠️ Bloqueio comercial (BLOQUEADO/CANCELADO) NÃO é validado aqui.
        // Motivo: estava causando regressão sistêmica — trial de 30 dias expirava,
        // AssinaturaJob marcava como BLOQUEADO e o filtro derrubava TODAS as
        // chamadas autenticadas (KPIs, dashboard, pedidos), parecendo "API offline".
        // Autenticação ≠ autorização comercial. Quando o módulo de assinatura
        // estiver completo, este check volta em endpoints específicos via
        // @PreAuthorize/AOP, não como pré-condição global do filter chain.

        // ✅ CORREÇÃO: authority montada direto do token com prefixo ROLE_
        if (SecurityContextHolder.getContext().getAuthentication() == null && role != null) {
            List<SimpleGrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + role));

            UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(email, null, authorities);
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        filterChain.doFilter(request, response);
    }
}