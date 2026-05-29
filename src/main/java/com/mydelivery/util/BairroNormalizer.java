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
