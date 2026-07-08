package com.mydelivery.equipe;

/**
 * ATIVO      — pode logar, JWT válido, aparece no menu do dono.
 * BLOQUEADO  — não pode logar. Bloqueio incrementa tokenVersion do membro,
 *              o que invalida INSTANTANEAMENTE qualquer JWT emitido antes
 *              (filter compara versão do JWT com versão no banco).
 */
public enum StatusMembro {
    ATIVO,
    BLOQUEADO;

    public String getLabel() {
        return this == ATIVO ? "Ativo" : "Bloqueado";
    }
}
