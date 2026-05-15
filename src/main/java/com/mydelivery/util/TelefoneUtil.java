package com.mydelivery.util;

/**
 * Helper de normalização de telefones.
 *
 * Razão de existir: o cliente digita "(11) 91234-5678" no front e a máscara
 * é mantida ao enviar pro backend. Quando o front consulta saldo de pontos
 * ou valida cupom, manda só dígitos ("11912345678"). Sem normalização
 * consistente, as buscas por telefone falham silenciosamente.
 *
 * Convenção: SEMPRE armazenamos e comparamos telefones APENAS COM DÍGITOS.
 */
public final class TelefoneUtil {

    private TelefoneUtil() {}

    /**
     * Remove tudo que não é dígito. Retorna null se a entrada for null/blank.
     */
    public static String normalizar(String tel) {
        if (tel == null) return null;
        String apenasDigitos = tel.replaceAll("\\D", "");
        return apenasDigitos.isEmpty() ? null : apenasDigitos;
    }
}
