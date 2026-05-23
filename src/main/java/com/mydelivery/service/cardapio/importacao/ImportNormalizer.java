package com.mydelivery.service.cardapio.importacao;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.mydelivery.service.cardapio.importacao.dto.CategoriaImportada;
import com.mydelivery.service.cardapio.importacao.dto.ProdutoImportado;
import com.mydelivery.service.cardapio.importacao.dto.ResultadoImport;

/**
 * Limpa e valida o resultado bruto antes de devolver pro frontend.
 * Garante:
 *  - Nome obrigatório (item descartado se faltar)
 *  - Preço >= 0 (item descartado se ausente)
 *  - Descrição máx 500 chars
 *  - Imagem URL com http(s)
 *  - Categoria nunca null
 *  - Score por item calculado de forma consistente
 *  - Score do resultado = média dos itens
 */
@Component
public class ImportNormalizer {

    private static final int MAX_DESC = 500;
    private static final int MAX_NOME = 120;

    public ResultadoImport normalizar(ResultadoImport in) {
        if (in == null) return null;
        List<CategoriaImportada> catsLimpas = new ArrayList<>();
        int totalScore = 0, totalItens = 0;
        int descartadosSemPreco = 0, descartadosSemNome = 0;

        if (in.getCategorias() != null) for (CategoriaImportada cat : in.getCategorias()) {
            String nomeCat = clean(cat.getNome(), 80);
            if (isBlank(nomeCat)) nomeCat = "Sem categoria";

            List<ProdutoImportado> prodsLimpos = new ArrayList<>();
            if (cat.getProdutos() != null) for (ProdutoImportado p : cat.getProdutos()) {
                String nome = clean(p.getNome(), MAX_NOME);
                if (isBlank(nome)) { descartadosSemNome++; continue; }
                BigDecimal preco = normalizarPreco(p.getPreco());
                if (preco == null) { descartadosSemPreco++; continue; }

                String desc = clean(p.getDescricao(), MAX_DESC);
                String img = validarUrl(p.getImagemUrl());

                int score = 50; // tem nome + preço
                if (!isBlank(desc)) score += 20;
                if (!isBlank(img)) score += 20;
                if (!isBlank(nomeCat) && !"Sem categoria".equals(nomeCat)) score += 10;

                prodsLimpos.add(ProdutoImportado.builder()
                        .nome(nome)
                        .descricao(desc)
                        .preco(preco)
                        .imagemUrl(img)
                        .categoriaSugerida(nomeCat)
                        .score(score)
                        .build());
                totalScore += score;
                totalItens++;
            }
            if (!prodsLimpos.isEmpty()) {
                catsLimpas.add(CategoriaImportada.builder()
                        .nome(nomeCat)
                        .produtos(prodsLimpos)
                        .build());
            }
        }

        int scoreFinal = totalItens == 0 ? 0 : totalScore / totalItens;
        // Bônus por provider específico vs fallback genérico
        if (in.getProvider() != null && !"html-generico".equals(in.getProvider())
                && !"json-ld".equals(in.getProvider()) && !"next-data".equals(in.getProvider())) {
            scoreFinal = Math.min(100, scoreFinal + 10);
        }

        List<String> avisos = in.getAvisos() == null ? new ArrayList<>() : new ArrayList<>(in.getAvisos());
        if (descartadosSemNome > 0) avisos.add(descartadosSemNome + " produto(s) sem nome foram ignorados.");
        if (descartadosSemPreco > 0) avisos.add(descartadosSemPreco + " produto(s) sem preço foram ignorados.");
        long semImg = catsLimpas.stream().flatMap(c -> c.getProdutos().stream())
                .filter(p -> isBlank(p.getImagemUrl())).count();
        if (semImg > 0) avisos.add(semImg + " produto(s) sem imagem (você pode adicionar manualmente depois).");

        return ResultadoImport.builder()
                .provider(in.getProvider())
                .score(scoreFinal)
                .categorias(catsLimpas)
                .avisos(avisos)
                .urlOrigem(in.getUrlOrigem())
                .build();
    }

    // ── helpers ──

    private static String clean(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("<[^>]+>", " ")     // tira tags HTML residuais
                    .replaceAll("\\s+", " ")
                    .trim();
        if (t.length() > max) t = t.substring(0, max).trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private static String validarUrl(String s) {
        if (isBlank(s)) return null;
        String t = s.trim();
        if (t.startsWith("//")) t = "https:" + t;            // protocol-relative
        if (!t.startsWith("http://") && !t.startsWith("https://")) return null;
        if (t.length() > 1000) return null;
        return t;
    }

    /** Aceita BigDecimal direto ou null. Rejeita negativos. Arredonda pra 2 casas. */
    private static BigDecimal normalizarPreco(BigDecimal preco) {
        if (preco == null) return null;
        if (preco.signum() < 0) return null;
        return preco.setScale(2, RoundingMode.HALF_UP);
    }
}
