package com.mydelivery.crm;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

public final class CrmIdentity {
    private CrmIdentity() {}
    public static String text(Object value) { return value == null ? "" : value.toString().trim(); }
    public static String normalized(String value) {
        return Normalizer.normalize(text(value), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().replaceAll(" +", " ");
    }
    public static String phone(Object value) {
        String raw = text(value);
        if (raw.isBlank()) return "";
        if (raw.contains("@") && !raw.endsWith("@s.whatsapp.net") && !raw.endsWith("@c.us"))
            throw new IllegalArgumentException("Identificador WhatsApp sem telefone reconhecido.");
        String digits = raw.split("@")[0].replaceAll("\\D", "");
        if (digits.length() == 10 || digits.length() == 11) digits = "55" + digits;
        if (!digits.matches("[1-9][0-9]{10,14}")) throw new IllegalArgumentException("Informe telefone com DDD e código do país.");
        return digits;
    }
    public static String phoneKey(Object value) {
        String p = phone(value);
        // Brazilian mobile numbers with/without ninth digit refer to the same contact.
        if (p.startsWith("55") && p.length() == 13 && p.charAt(4) == '9') p = p.substring(0, 4) + p.substring(5);
        return p;
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static Set<String> keys(Map<String, Object> profile) {
        Set<String> result = new LinkedHashSet<>();
        for (String field : List.of("telefone", "whatsapp")) {
            String p = phoneKey(profile.get(field)); if (!p.isBlank()) result.add("phone:" + p);
        }
        String ig = text(profile.get("instagram")).toLowerCase(Locale.ROOT)
            .replaceFirst("^(https?://)?(www\\.)?instagram\\.com/", "").replaceFirst("^@", "").split("[/?#]")[0];
        if (!ig.isBlank()) result.add("instagram:" + hash(ig));
        String site = text(profile.get("site"));
        if (!site.isBlank()) {
            try {
                URI u = URI.create(site.matches("(?i)^https?://.*") ? site : "https://" + site);
                if (u.getHost() == null || u.getUserInfo() != null || !Set.of("http", "https").contains(u.getScheme())) throw new IllegalArgumentException();
                String identity = u.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "") + text(u.getPath()).replaceAll("/+$", "");
                result.add("site:" + hash(identity));
            } catch (Exception e) { throw new IllegalArgumentException("Site inválido. Informe um endereço http ou https."); }
        }
        String name = normalized(text(profile.get("nome"))), city = normalized(text(profile.get("cidade"))), state = normalized(text(profile.get("estado")));
        if (!name.isBlank() && !city.isBlank() && !state.isBlank()) result.add("place:" + hash(name + "|" + city + "|" + state));
        return result;
    }
}
