package com.mydelivery.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Campo {@code email} é um IDENTIFICADOR unificado: pode ser email do dono
 * OU login do membro. AuthService detecta pela presença de "@" e roteia.
 *
 * Nome do campo mantido como "email" pra retrocompat com o frontend antigo
 * que sempre postou {"email": "...", "senha": "..."}. Removida @Email pra
 * aceitar logins de membro (sem @). Validação real acontece no service.
 */
@Data
public class LoginRequest {

    @NotBlank(message = "Login ou e-mail é obrigatório")
    private String email;

    @NotBlank(message = "Senha é obrigatória")
    private String senha;
}