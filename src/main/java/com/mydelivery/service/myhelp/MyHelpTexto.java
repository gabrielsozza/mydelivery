package com.mydelivery.service.myhelp;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Normalização tolerante pro myHelp — mesma ideia do BairroNormalizer:
 * minúsculas, sem acento, sem pontuação, espaços colapsados. Assim "X-Tudo",
 * "x tudo" e "xtudo" viram a mesma chave de comparação.
 */
public final class MyHelpTexto {

    private MyHelpTexto() {}

    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        return n;
    }

    /** Tokens únicos (>=2 chars) do texto já normalizado. */
    public static Set<String> tokens(String norm) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : norm.split(" ")) {
            if (t.length() >= 2) out.add(t);
        }
        return out;
    }

    /** Distância de Levenshtein (para desempate fuzzy em nomes curtos). */
    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    /** Palavras que não ajudam a identificar o produto (comando/ligação). */
    public static final Set<String> STOPWORDS = new LinkedHashSet<>(Arrays.asList(
        "o", "a", "os", "as", "de", "do", "da", "dos", "das", "pra", "para", "por",
        "pro", "no", "na", "em", "que", "e", "com", "um", "uma", "meu", "minha",
        "altera", "alterar", "muda", "mudar", "troca", "trocar", "coloca", "colocar",
        "poe", "poem", "bota", "botar", "deixa", "deixar", "atualiza", "atualizar",
        "ajusta", "ajustar", "sobe", "subir", "aumenta", "aumentar", "abaixa", "abaixar",
        "preco", "precos", "valor", "valores", "custa", "custo", "reais", "real", "rs"
    ));
}
