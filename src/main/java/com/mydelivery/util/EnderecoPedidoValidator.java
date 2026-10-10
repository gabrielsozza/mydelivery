package com.mydelivery.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** Valida antes de gravar o cliente, preservando o endereço sem truncamento. */
public final class EnderecoPedidoValidator {
    private EnderecoPedidoValidator() {}

    public static Map<String, String> validar(Map<String, String> endereco) {
        if (endereco == null) throw new IllegalArgumentException("Preencha o endereço de entrega.");
        Map<String, String> resultado = new LinkedHashMap<>(endereco);
        campo(resultado, "rua", "Rua / Avenida", 200, true);
        campo(resultado, "numero", "Número", 120, true);
        campo(resultado, "complemento", "Complemento", 120, false);
        campo(resultado, "bairro", "Bairro", 120, true);
        campo(resultado, "referencia", "Referência", 200, false);
        campo(resultado, "cidade", "Cidade", 120, false);
        campo(resultado, "estado", "Estado", 10, false);
        campo(resultado, "cep", "CEP", 10, false);
        return resultado;
    }

    private static void campo(Map<String, String> endereco, String chave, String nome, int limite, boolean obrigatorio) {
        String valor = endereco.get(chave);
        if (valor != null) valor = valor.trim();
        if (obrigatorio && (valor == null || valor.isEmpty()))
            throw new IllegalArgumentException("Preencha o campo " + nome + " do endereço.");
        if (valor != null && valor.length() > limite)
            throw new IllegalArgumentException("O campo " + nome + " deve ter até " + limite + " caracteres. Distribua os detalhes entre complemento e referência.");
        endereco.put(chave, valor);
    }
}
