package com.mydelivery.service.myhelp;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Carrega a base de domínio alimentício (dominio-alimentos-br.json) na memória
 * e infere a CATEGORIA provável de um texto. É REFORÇO: o motor sempre tenta
 * casar com os produtos reais da loja primeiro; esta base entra quando o match
 * direto falha, pra sugerir a categoria certa (ex.: "franguinho" → PORCOES).
 */
@Slf4j
@Component
public class MyHelpDominio {

    /** termo normalizado → tag da categoria (ex.: "xtudo" → "LANCHES"). */
    private final Map<String, String> termoParaCategoria = new HashMap<>();

    @PostConstruct
    public void carregar() {
        try (InputStream is = new ClassPathResource("myhelp/dominio-alimentos-br.json").getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(is);
            int cats = 0;
            for (JsonNode cat : root.path("categorias")) {
                String tag = cat.path("tag").asText(null);
                if (tag == null) continue;
                cats++;
                indexar(cat.path("termos"), tag);
                indexar(cat.path("apelidos_categoria"), tag);
            }
            log.info("[myHelp] KB carregada: {} termos em {} categorias", termoParaCategoria.size(), cats);
        } catch (Exception e) {
            log.warn("[myHelp] falha ao carregar KB (segue sem reforço de domínio): {}", e.getMessage());
        }
    }

    private void indexar(JsonNode arr, String tag) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode t : arr) {
            String n = MyHelpTexto.norm(t.asText(""));
            if (!n.isBlank()) termoParaCategoria.putIfAbsent(n, tag);
        }
    }

    /**
     * Infere a categoria provável (tag) a partir do texto normalizado. Retorna
     * {@code null} se nada casar. Vota pelo tamanho do termo casado (termos mais
     * específicos pesam mais).
     */
    public String inferirCategoria(String textoNorm) {
        if (textoNorm == null || textoNorm.isBlank()) return null;
        if (termoParaCategoria.containsKey(textoNorm)) return termoParaCategoria.get(textoNorm);
        Map<String, Integer> votos = new HashMap<>();
        for (Map.Entry<String, String> e : termoParaCategoria.entrySet()) {
            String termo = e.getKey();
            // casa termo do KB contido no texto (palavra inteira ou trecho relevante)
            if (termo.length() >= 3 && (" " + textoNorm + " ").contains(" " + termo + " ")) {
                votos.merge(e.getValue(), termo.length() * 2, Integer::sum);
            } else if (termo.length() >= 4 && textoNorm.contains(termo)) {
                votos.merge(e.getValue(), termo.length(), Integer::sum);
            }
        }
        return votos.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
