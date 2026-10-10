package com.mydelivery.util;

import java.text.Normalizer;

/**
 * Normalização tolerante de nomes de bairro pra matching cadastrado × digitado.
 *
 * Transformações aplicadas (na ordem):
 *  1. NFD + remove acentos:           "Capão"        → "capao"
 *  2. lowercase + trim
 *  3. Pontuação ({@code .}, {@code -}, {@code ,}, {@code _}, {@code /}) vira espaço
 *  4. Espaços múltiplos viram 1
 *  5. Abreviações comuns expandidas:  "sta" → "santa", "sto" → "santo",
 *                                     "s " (no início) → "sao ",
 *                                     "dr" → "doutor", "pe" → "padre",
 *                                     "pres" → "presidente"
 *  6. Romanos isolados viram árabicos: I→1, II→2, III→3, IV→4, V→5,
 *                                       VI→6, VII→7, VIII→8, IX→9, X→10
 *
 * Exemplos de match (ambos viram a mesma string normalizada):
 *  - "Serra Dourada II"  ≡  "serra dourada 2"  ≡  "Serra-Dourada II"
 *  - "Capão Redondo"     ≡  "capao redondo"    ≡  "CAPAO  REDONDO"
 *  - "Vila São José"     ≡  "vila sao jose"    ≡  "Vl. S. Jose"
 *
 * IMPORTANTE: a substituição de romanos ocorre apenas em palavras inteiras
 * (delimitadas por espaço/início/fim) pra não bagunçar nomes que contenham
 * essas letras dentro de outras palavras (ex: "Vila" mantém intacto).
 */
public final class BairroNormalizer {

    private BairroNormalizer() {}

    public static String normalizar(String s) {
        if (s == null) return "";
        String r = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase().trim()
                // pontuação → espaço
                .replaceAll("[\\.\\-,_/]+", " ")
                // espaços múltiplos → 1
                .replaceAll("\\s+", " ");

        // Abreviações comuns (precisam vir ANTES dos romanos pra "s." não virar "5")
        r = r.replaceAll("(?<=^|\\s)sta(?=\\s|$)", "santa")
             .replaceAll("(?<=^|\\s)sto(?=\\s|$)", "santo")
             .replaceAll("(?<=^|\\s)s(?=\\s)", "sao")
             .replaceAll("(?<=^|\\s)dr(?=\\s|$)", "doutor")
             .replaceAll("(?<=^|\\s)pe(?=\\s|$)", "padre")
             .replaceAll("(?<=^|\\s)pres(?=\\s|$)", "presidente")
             .replaceAll("(?<=^|\\s)av(?=\\s|$)", "avenida")
             .replaceAll("(?<=^|\\s)vl(?=\\s|$)", "vila")
             .replaceAll("(?<=^|\\s)jd(?=\\s|$)", "jardim");

        // Romanos → árabicos (ordem importa: maior antes do menor!)
        r = r.replaceAll("(?<=^|\\s)viii(?=\\s|$)", "8")
             .replaceAll("(?<=^|\\s)vii(?=\\s|$)",  "7")
             .replaceAll("(?<=^|\\s)iii(?=\\s|$)",  "3")
             .replaceAll("(?<=^|\\s)ix(?=\\s|$)",   "9")
             .replaceAll("(?<=^|\\s)iv(?=\\s|$)",   "4")
             .replaceAll("(?<=^|\\s)vi(?=\\s|$)",   "6")
             .replaceAll("(?<=^|\\s)ii(?=\\s|$)",   "2")
             .replaceAll("(?<=^|\\s)x(?=\\s|$)",    "10")
             .replaceAll("(?<=^|\\s)v(?=\\s|$)",    "5")
             .replaceAll("(?<=^|\\s)i(?=\\s|$)",    "1");

        return r.trim();
    }

    /** Resolve variações de escrita sem misturar números de bairros ou candidatos próximos. */
    public static com.mydelivery.model.BairroEntrega encontrar(
            java.util.List<com.mydelivery.model.BairroEntrega> bairros, String nome) {
        String digitado = normalizar(nome);
        if (bairros == null || digitado.isEmpty() || digitado.length() > 120) return null;
        var candidatos = new java.util.LinkedHashMap<String, com.mydelivery.model.BairroEntrega>();
        for (var bairro : bairros) {
            if (bairro != null && bairro.getNome() != null) {
                String chave = normalizar(bairro.getNome());
                if (!chave.isEmpty()) candidatos.putIfAbsent(chave, bairro);
            }
        }
        if (candidatos.containsKey(digitado)) return candidatos.get(digitado);

        // 1/I e 2/II são equivalentes, mas 1 e 2 nunca são erros de digitação presumidos.
        String numeros = numeros(digitado);
        var compativeis = candidatos.entrySet().stream()
                .filter(e -> numeros(e.getKey()).equals(numeros)).toList();
        var parciais = compativeis.stream().filter(e -> contemNome(e.getKey(), digitado)
                || contemNome(digitado, e.getKey())).toList();
        if (parciais.size() == 1) return parciais.getFirst().getValue();
        if (parciais.size() > 1 || digitado.length() < 5) return null;

        // Até duas pequenas edições; exige vantagem clara sobre o segundo candidato.
        int melhor = Integer.MAX_VALUE, segundo = Integer.MAX_VALUE;
        com.mydelivery.model.BairroEntrega resultado = null;
        for (var candidato : compativeis) {
            String cadastrado = candidato.getKey();
            if (cadastrado.length() < 5 || Math.abs(cadastrado.length() - digitado.length()) > 2) continue;
            int distancia = distanciaEdicao(digitado, cadastrado);
            if (distancia < melhor) {
                segundo = melhor;
                melhor = distancia;
                resultado = candidato.getValue();
            } else if (distancia < segundo) segundo = distancia;
        }
        int limite = digitado.length() >= 12 ? 2 : 1;
        return melhor <= limite && (segundo == Integer.MAX_VALUE || segundo - melhor >= 2)
                ? resultado : null;
    }

    private static String numeros(String nome) {
        return java.util.regex.Pattern.compile("\\d+").matcher(nome).results()
                .map(java.util.regex.MatchResult::group).collect(java.util.stream.Collectors.joining(","));
    }

    private static boolean contemNome(String completo, String trecho) {
        return (" " + completo + " ").contains(" " + trecho + " ");
    }

    /** Inserção, remoção, substituição e troca de duas letras vizinhas. */
    private static int distanciaEdicao(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int custo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + custo);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
            }
        }
        return d[a.length()][b.length()];
    }

    /**
     * Match tolerante entre 2 nomes de bairro. True se um normalizado
     * contém o outro (suficiente pra "Serra Dourada" casar com "Serra Dourada II"
     * por exemplo, mas evitando falsos positivos com strings vazias).
     */
    public static boolean combina(String cadastrado, String digitado) {
        String a = normalizar(cadastrado);
        String b = normalizar(digitado);
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.contains(b) || b.contains(a);
    }
}
