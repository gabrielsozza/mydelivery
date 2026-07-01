package com.mydelivery.service.afiliados;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Lançada quando o cadastro é bloqueado por indícios de autoindicação.
 * Retorna 409 CONFLICT — o Handler exibe mensagem clara pro usuário.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class AutoindicacaoException extends RuntimeException {
    private final String flagsDetectadas;

    public AutoindicacaoException(String flagsDetectadas) {
        super("Cadastro bloqueado: os dados informados batem com os dados do próprio "
                + "afiliado responsável pelo link. Autoindicação não é permitida no programa. "
                + "Se você acredita que isso é um engano, entre em contato pelo suporte.");
        this.flagsDetectadas = flagsDetectadas;
    }

    public String getFlagsDetectadas() { return flagsDetectadas; }
}
