package com.mydelivery.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final LoginRateLimitFilter loginRateLimitFilter;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session
                        -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers("/public/**").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/cardapio/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/cardapio/*/banners").permitAll()
                .requestMatchers("/api/pedidos/novo").permitAll()
                .requestMatchers("/api/pedidos/*/acompanhar").permitAll()
                .requestMatchers("/api/restaurante/publico/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/publico/**").permitAll()
                // Uploads (logo/capa do restaurante, etc.) — públicos pra cardápio do cliente
                .requestMatchers("/uploads/**").permitAll()
                // carrinho: só POST e DELETE são públicos (cardápio do cliente)
                .requestMatchers(HttpMethod.POST, "/api/carrinho").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/api/carrinho/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/carrinho/**").authenticated()
                .requestMatchers(HttpMethod.PATCH, "/api/carrinho/**").authenticated()
                // Fidelidade e cupons: endpoints públicos pro cardápio do cliente
                .requestMatchers(HttpMethod.GET, "/api/fidelidade/*/status").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/cupons/publico/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/cupons/validar").permitAll()
                // Pagamentos: cliente final consulta status pra polling do PIX (sem auth)
                .requestMatchers(HttpMethod.GET, "/api/pagamentos/pedido/**").permitAll()
                // Pagamento online (PIX/cartão) — cliente final, idempotente, multi-tenant via pedidoId
                .requestMatchers(HttpMethod.POST, "/api/pagamentos/pix").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/pagamentos/cartao").permitAll()
                // Webhook do Mercado Pago — público, autenticação via HMAC dentro do handler
                .requestMatchers(HttpMethod.POST, "/api/webhooks/mercadopago").permitAll()
                // Webhook da Evolution API (WhatsApp) — público, validação pelo nome da instância no path
                .requestMatchers(HttpMethod.POST, "/api/webhooks/whatsapp/**").permitAll()
                // Webhook do iFood — público, validação HMAC (opt-in strict) dentro do controller
                .requestMatchers(HttpMethod.POST, "/api/webhooks/ifood").permitAll()
                // Endpoint interno chamado pelo admin-mydelivery-api — autenticado via X-Admin-Secret
                .requestMatchers(HttpMethod.POST, "/api/restaurante/assinatura/conceder-meses-gratis-admin").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/restaurante/assinatura/expirar-trial-admin").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/restaurante/assinatura/precificar-restaurante-admin").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/restaurante/assinatura/impersonar-admin").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/restaurante/assinatura/replicar-cardapio-admin").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/restaurante/assinatura/reconciliar-pagamento-admin").permitAll()
                // Health do WhatsApp — autenticação via X-Admin-Secret validada no controller
                .requestMatchers("/api/admin-internal/whatsapp/**").permitAll()
                // Web Push: setup VAPID — autenticado via X-Admin-Secret no controller
                .requestMatchers("/api/admin-internal/web-push/**").permitAll()
                // Garçom: login PIN é público (recebe pin no body); demais endpoints
                // /api/garcom/** exigem role GARCOM (JWT gerado no login).
                .requestMatchers(HttpMethod.POST, "/api/garcom/*/login").permitAll()
                .requestMatchers("/api/garcom/**").hasRole("GARCOM")
                // Entregador: mesmo padrão do garçom — login PIN público,
                // resto exige role ENTREGADOR (JWT com sub=entregadorId)
                .requestMatchers(HttpMethod.POST, "/api/entregador/*/login").permitAll()
                .requestMatchers("/api/entregador/**").hasRole("ENTREGADOR")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/restaurante/**").hasAnyRole("RESTAURANTE", "ADMIN")
                .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)
                // Rate-limit em endpoints de login (brute-force). Roda ANTES do
                // JWT filter — se IP estourou limite, retorna 429 sem nem parsear
                // token.
                .addFilterBefore(loginRateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS resiliente (Jul/2026): usa {@code setAllowedOriginPatterns} em vez
     * de {@code setAllowedOrigins} pra aceitar wildcards em subdomínios.
     *
     * <p>Motivo do refactor: cliente estava tomando <b>403 em
     * POST /api/pedidos/novo</b> porque o CORS_ORIGINS da Railway não
     * cobria domínios custom recém-criados (subdomínio de teste, Netlify
     * preview, dominio-próprio-por-loja). O Spring rejeita CORS ANTES de
     * chegar no controller — endpoint em permitAll não adianta se o
     * Origin não bater.
     *
     * <p>Padrões default sempre aceitos (independem de env var):
     * <ul>
     *   <li>{@code https://*.mydeliveryfood.com.br} + apex</li>
     *   <li>{@code https://*.mydelivery.app} + apex</li>
     *   <li>{@code https://*.netlify.app} (preview/deploy do frontend)</li>
     *   <li>{@code https://*.railway.app} (endpoints staging)</li>
     *   <li>localhost e file:// pra dev local</li>
     * </ul>
     * O que vier de {@code CORS_ORIGINS} é SOMADO (não substitui).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // WILDCARD TOTAL: aceita qualquer Origin. Trade-off pesado avaliado:
        //  - Endpoints privados (painel restaurante, admin) exigem JWT válido,
        //    então CORS não é a camada de defesa deles — é o token.
        //  - Endpoints públicos (cardápio, criar pedido, webhooks) precisam
        //    aceitar N domínios de N restaurantes (Netlify, custom, subdomínios).
        //    Enumerar patterns era jogo perdido — sempre aparecia um novo.
        //
        // Spring Security 6 aceita "*" em setAllowedOriginPatterns COM
        // credentials=true (diferente de setAllowedOrigins que proíbe).
        // Antes desse fix o server rejeitava POST /api/pedidos/novo com 403 pra
        // qualquer origem fora da lista fixa — ninguém conseguia fazer pedido.
        config.setAllowedOriginPatterns(java.util.List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Correlation-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        return request -> config;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
