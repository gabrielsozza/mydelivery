package com.mydelivery.config;

import java.util.concurrent.TimeUnit;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Cache em memória pra endpoints públicos hot. TTLs intencionalmente curtos
 * (30-60s) — restaurante muda cardápio várias vezes ao dia; preferimos cache
 * vencer rápido a invalidação manual em todo método de update (frágil).
 *
 * <p>Caches declarados:
 * <ul>
 *   <li>{@code cardapio} (60s) — payload pesado de produtos + categorias
 *       pra cardápio público do cliente final.
 *   <li>{@code restaurante-publico} (120s) — config visível (nome, logo,
 *       horário, taxa) — quase nunca muda durante o dia.
 *   <li>{@code painel-chamada} (5s) — TV da loja; precisa ser quase
 *       tempo-real, mas mesmo 5s elimina 90% dos hits ao banco (TV pola
 *       a cada 3s; cliente puxa a cada poucos segundos).
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager();
        mgr.registerCustomCache("cardapio",
                Caffeine.newBuilder()
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .maximumSize(500)
                        .build());
        // Restaurante público: 10s só. Aceitando-pedidos é calculado on-the-fly
        // baseado em hora atual + estado aberto/fechado/cutoff — TTL maior
        // criaria janela de "aberto fantasma" depois do fechamento.
        mgr.registerCustomCache("restaurante-publico",
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.SECONDS)
                        .maximumSize(500)
                        .build());
        mgr.registerCustomCache("painel-chamada",
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.SECONDS)
                        .maximumSize(500)
                        .build());
        mgr.registerCustomCache("cardapio-banners",
                Caffeine.newBuilder()
                        .expireAfterWrite(120, TimeUnit.SECONDS)
                        .maximumSize(500)
                        .build());
        return mgr;
    }
}
