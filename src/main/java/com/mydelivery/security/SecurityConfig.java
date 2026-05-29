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
                // Endpoint interno chamado pelo admin-mydelivery-api — autenticado via X-Admin-Secret
                .requestMatchers(HttpMethod.POST, "/api/restaurante/assinatura/conceder-meses-gratis-admin").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/restaurante/assinatura/impersonar-admin").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/restaurante/**").hasAnyRole("RESTAURANTE", "ADMIN")
                .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
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
