package com.mydelivery.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponse {
    private String accessToken;
    private String refreshToken;
    private String nome;
    private String email;
    private String role;
    private String restauranteSlug;
    // ── Novos campos (Fase Equipe) — só populados quando o login é de MEMBRO.
    // Frontend usa pra saber que é login de equipe e ajustar sidebar/UI.
    // Dono continua recebendo null nesses campos (retrocompat total).
    /** Ex: "PROPRIETARIO" | "GERENTE" | "FUNCIONARIO". Null pro proprietário. */
    private String cargo;
    /** CSV de nomes de Permissao. Null pro proprietário (que tem todas). */
    private String permissoes;
}