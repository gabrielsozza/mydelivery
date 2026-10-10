package com.mydelivery.service.mercadopago;

import com.mydelivery.model.ConfiguracaoRestaurante;

/** Credenciais locais necessárias para cobrar e confirmar pagamentos da loja. */
public final class MercadoPagoDisponibilidade {
    private MercadoPagoDisponibilidade() {}

    public static boolean pix(ConfiguracaoRestaurante cfg) {
        return cfg != null && preenchido(cfg.getMpAccessToken()) && preenchido(cfg.getMpWebhookSecret());
    }

    public static boolean cartao(ConfiguracaoRestaurante cfg) {
        return pix(cfg) && preenchido(cfg.getMpPublicKey());
    }

    public static String pendencias(ConfiguracaoRestaurante cfg) {
        var faltam = new java.util.ArrayList<String>();
        if (cfg == null || !preenchido(cfg.getMpAccessToken())) faltam.add("Access Token de produção");
        if (cfg == null || !preenchido(cfg.getMpWebhookSecret())) faltam.add("assinatura secreta do webhook");
        if (cfg == null || !preenchido(cfg.getMpPublicKey())) faltam.add("Public Key para cartão");
        return faltam.isEmpty() ? "Credenciais preenchidas. Valide a conexão e confira se Pix e crédito estão habilitados em Configurações."
                : "Falta cadastrar: " + String.join(", ", faltam) + ".";
    }

    private static boolean preenchido(String valor) {
        return valor != null && !valor.isBlank();
    }
}
