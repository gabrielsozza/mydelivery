package com.mydelivery.service.ifood;

/**
 * Exception que preserva status HTTP + corpo original da resposta iFood.
 *
 * <p>Usado pra propagar erros de forma transparente até o controller, que
 * devolve um 502 Bad Gateway com o corpo original do iFood — assim o
 * frontend consegue mostrar a mensagem exata (ex: "Merchant not found",
 * "Invalid opening hours format") em vez de um 400/500 genérico.
 */
public class IfoodApiException extends RuntimeException {

    /** Status HTTP retornado pelo iFood (ex: 400, 404, 401). */
    private final int statusIfood;
    /** Corpo bruto da resposta do iFood (pode ser JSON string ou texto). */
    private final String bodyIfood;

    public IfoodApiException(int statusIfood, String bodyIfood, String contextoAcao) {
        super("iFood " + statusIfood + " em " + contextoAcao + ": " + bodyIfood);
        this.statusIfood = statusIfood;
        this.bodyIfood = bodyIfood;
    }

    public int getStatusIfood() { return statusIfood; }
    public String getBodyIfood() { return bodyIfood; }
}
