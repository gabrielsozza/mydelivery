package com.mydelivery.service.afiliados;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detecta se o cadastro de um restaurante tem indícios de autoindicação
 * (afiliado indicando ele mesmo pra ganhar comissão).
 *
 * Estratégia: compara dados normalizados do CADASTRO (usuário/restaurante)
 * contra dados do AFILIADO buscados no myafiliados-api. Qualquer match forte
 * é indicativo suficiente pra bloquear.
 *
 * A regra é conservadora — preferimos bloquear e o usuário legítimo abrir
 * ticket do que deixar passar fraude.
 */
public class AutoindicacaoDetector {

    /**
     * Executa comparação e devolve resultado. Se {@code temMatchForte()} for
     * true, o cadastro DEVE ser bloqueado.
     */
    public static Resultado detectar(DadosCadastro cadastro, Map<String, Object> snapAfiliado) {
        Resultado r = new Resultado();
        if (snapAfiliado == null || snapAfiliado.isEmpty()) return r; // sem afiliado = sem match

        String emailAf = normalizarEmail((String) snapAfiliado.get("email"));
        String telAf   = normalizarTelefone((String) snapAfiliado.get("telefone"));
        String cpfAf   = normalizarDocumento((String) snapAfiliado.get("cpf"));
        String pixAf   = normalizarPix((String) snapAfiliado.get("chavePix"));

        String emailCad = normalizarEmail(cadastro.email);
        String telCad   = normalizarTelefone(cadastro.telefone);
        String cpfCad   = normalizarDocumento(cadastro.cpfCnpj);
        String pixCad   = normalizarPix(cadastro.chavePix);

        if (naoVaziosIguais(emailAf, emailCad))   r.flags.add("email");
        if (naoVaziosIguais(telAf, telCad))       r.flags.add("telefone");
        if (naoVaziosIguais(cpfAf, cpfCad))       r.flags.add("cpf_cnpj");
        if (naoVaziosIguais(pixAf, pixCad))       r.flags.add("chave_pix");

        // Sinal fraco extra: se o e-mail do cadastro e do afiliado
        // compartilham o mesmo prefixo antes do @ (ex: "joao.silva@x.com" e
        // "joao.silva@y.com"). Não bloqueia, mas marca pra revisão.
        if (emailAf != null && emailCad != null) {
            String prefAf = emailAf.split("@")[0];
            String prefCad = emailCad.split("@")[0];
            if (prefAf.length() >= 4 && prefAf.equalsIgnoreCase(prefCad)
                    && !emailAf.equals(emailCad)) {
                r.flagsFraco.add("prefixo_email");
            }
        }
        return r;
    }

    public static class DadosCadastro {
        public String email;
        public String telefone;
        public String cpfCnpj;
        public String chavePix;
    }

    public static class Resultado {
        public final List<String> flags = new ArrayList<>();
        public final List<String> flagsFraco = new ArrayList<>();

        /** True quando há match em ao menos um identificador forte. Bloqueia. */
        public boolean temMatchForte() { return !flags.isEmpty(); }

        /** Descrição legível dos indicadores detectados. */
        public String descricao() {
            if (flags.isEmpty() && flagsFraco.isEmpty()) return "sem indícios";
            StringBuilder sb = new StringBuilder();
            if (!flags.isEmpty()) sb.append("match forte: ").append(String.join(", ", flags));
            if (!flagsFraco.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("suspeitas fracas: ").append(String.join(", ", flagsFraco));
            }
            return sb.toString();
        }

        public Map<String, Object> paraLog() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("flags", String.join(",", flags));
            m.put("flagsFraco", String.join(",", flagsFraco));
            return m;
        }
    }

    // ─── Normalizadores ──────────────────────────────────────────────

    static String normalizarEmail(String s) {
        if (s == null) return null;
        s = s.trim().toLowerCase();
        // Gmail ignora "." e "+tag" no local part. Trata como mesmo endereço.
        int at = s.indexOf('@');
        if (at > 0) {
            String local = s.substring(0, at);
            String dom = s.substring(at + 1);
            if ("gmail.com".equals(dom) || "googlemail.com".equals(dom)) {
                int plus = local.indexOf('+');
                if (plus > 0) local = local.substring(0, plus);
                local = local.replace(".", "");
                s = local + "@gmail.com";
            }
        }
        return s.isEmpty() ? null : s;
    }

    static String normalizarTelefone(String s) {
        if (s == null) return null;
        String d = s.replaceAll("\\D", "");
        // Normaliza celular BR com 55 na frente
        if (d.startsWith("55") && d.length() >= 12) d = d.substring(2);
        return d.length() < 8 ? null : d;
    }

    static String normalizarDocumento(String s) {
        if (s == null) return null;
        String d = s.replaceAll("\\D", "");
        return d.length() < 11 ? null : d;
    }

    static String normalizarPix(String s) {
        if (s == null) return null;
        String limpo = s.trim().toLowerCase();
        if (limpo.isEmpty()) return null;
        // Se parece CPF/CNPJ ou celular, normaliza como número
        if (limpo.matches("[\\d.\\-/() ]+")) {
            return limpo.replaceAll("\\D", "");
        }
        return limpo;
    }

    private static boolean naoVaziosIguais(String a, String b) {
        if (a == null || b == null) return false;
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equalsIgnoreCase(b);
    }
}
