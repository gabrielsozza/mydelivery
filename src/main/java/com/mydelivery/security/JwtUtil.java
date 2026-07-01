package com.mydelivery.security;

import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    @Value("${jwt.refresh-expiration}")
    private Long refreshExpiration;

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String gerarToken(String email, String role) {
        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Gera token CURTO (15min) pra suporte/admin entrar como restaurante.
     * Inclui claim {@code impersonatedBy} pra auditoria nos logs do main app.
     */
    public String gerarTokenImpersonacao(String email, String role, String adminIdentificador) {
        long quinzeMinutos = 15L * 60L * 1000L;
        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .claim("impersonatedBy", adminIdentificador)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + quinzeMinutos))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Gera token CURTO (30min) pra afiliado demonstrar o sistema pra prospect.
     * Marca claim {@code demo=true} — usada pelo DemoModeFilter pra bloquear
     * mutações destrutivas (envio de WhatsApp real, alteração de config sensível,
     * delete, pagamentos). Role é RESTAURANTE normal pra atravessar security
     * matchers já existentes sem duplicar todos os hasRole().
     */
    public String gerarTokenDemo(String email, String afiliadoIdentificador) {
        long trintaMinutos = 30L * 60L * 1000L;
        return Jwts.builder()
                .subject(email)
                .claim("role", "RESTAURANTE")
                .claim("demo", true)
                .claim("demoAfiliado", afiliadoIdentificador)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + trintaMinutos))
                .signWith(getSigningKey())
                .compact();
    }

    /** Retorna true se o token tem claim {@code demo=true}. Falha silenciosa → false. */
    public boolean isDemo(String token) {
        try {
            Object v = extrairClaims(token).get("demo");
            return v instanceof Boolean && ((Boolean) v);
        } catch (Exception e) { return false; }
    }

    public String gerarRefreshToken(String email) {
        return Jwts.builder()
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiration))
                .signWith(getSigningKey())
                .compact();
    }

    public String extrairEmail(String token) {
        return extrairClaims(token).getSubject();
    }

    public String extrairRole(String token) {
        return extrairClaims(token).get("role", String.class);
    }

    public boolean tokenValido(String token) {
        try {
            extrairClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims extrairClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}