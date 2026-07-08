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
     * Token pra MEMBRO da equipe. Subject continua = email do dono do
     * restaurante (mantém compat com @AuthenticationPrincipal String email
     * em 30+ controllers). Claims extras identificam o membro pro filter
     * poder aplicar as permissões dele.
     *
     * membroId + tokenVersion permitem invalidar sessão SEM blocklist:
     * bloqueio incrementa tokenVersion no DB → filter compara → 401.
     */
    public String gerarTokenMembro(String emailDono, String role, Long membroId, int tokenVersion) {
        return Jwts.builder()
                .subject(emailDono)
                .claim("role", role)
                .claim("membroId", membroId)
                .claim("tokenVersion", tokenVersion)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    /** null se JWT é de dono (sem claim membroId) ou token inválido. */
    public Long extrairMembroId(String token) {
        try {
            Number n = extrairClaims(token).get("membroId", Number.class);
            return n == null ? null : n.longValue();
        } catch (Exception e) { return null; }
    }

    /** 0 se JWT é de dono. Filter usa pra checar contra o valor no banco. */
    public int extrairTokenVersion(String token) {
        try {
            Number n = extrairClaims(token).get("tokenVersion", Number.class);
            return n == null ? 0 : n.intValue();
        } catch (Exception e) { return 0; }
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