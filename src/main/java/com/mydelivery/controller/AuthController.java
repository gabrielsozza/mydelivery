package com.mydelivery.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.equipe.PermissaoContext;

import com.mydelivery.dto.auth.CadastroRequest;
import com.mydelivery.dto.auth.LoginRequest;
import com.mydelivery.dto.auth.LoginResponse;
import com.mydelivery.dto.auth.RecuperarSenhaRequest;
import com.mydelivery.dto.auth.RedefinirSenhaRequest;
import com.mydelivery.service.AuthService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/cadastro")
    public ResponseEntity<LoginResponse> cadastrar(
            @Valid @RequestBody CadastroRequest request) {
        return ResponseEntity.ok(authService.cadastrar(request));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/recuperar-senha")
    public ResponseEntity<Map<String, String>> recuperarSenha(
            @Valid @RequestBody RecuperarSenhaRequest request) {
        authService.solicitarRecuperacaoSenha(request);
        return ResponseEntity.ok(Map.of(
            "mensagem", "E-mail de recuperação enviado com sucesso"));
    }

    @PostMapping("/redefinir-senha")
    public ResponseEntity<Map<String, String>> redefinirSenha(
            @Valid @RequestBody RedefinirSenhaRequest request) {
        authService.redefinirSenha(request);
        return ResponseEntity.ok(Map.of(
            "mensagem", "Senha redefinida com sucesso"));
    }

    /**
     * Troca senha do usuário LOGADO — precisa da senha atual pra confirmar
     * identidade. Alternativa ao fluxo esqueci-senha via email, pra quem
     * já está autenticado no painel.
     *
     * <p>Body: {@code { senhaAtual: "...", novaSenha: "..." }} — mínimo 6
     * chars. Retorna 200 em sucesso, 400 com mensagem em falha.
     */
    @PostMapping("/alterar-senha")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, String>> alterarSenha(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {
        String senhaAtual = body == null ? null : body.get("senhaAtual");
        String novaSenha  = body == null ? null : body.get("novaSenha");
        authService.alterarSenhaAutenticado(email, senhaAtual, novaSenha);
        return ResponseEntity.ok(Map.of("mensagem", "Senha alterada com sucesso"));
    }

    /**
     * Devolve o "quem sou eu" — cargo + permissões efetivas do JWT atual.
     * Frontend chama uma vez no boot pra montar sidebar e cachear em
     * localStorage.
     *
     * Proprietário → cargo="PROPRIETARIO" + permissoes com TODAS as enums.
     * Membro       → cargo do membro + permissões dele.
     *
     * Usado pra reconciliar quando dono editou permissões enquanto o
     * membro estava logado (mas o tokenVersion já teria invalidado a
     * sessão — este endpoint é o backup pra front sincronizar quando
     * relogar).
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal String email) {
        var ctx = PermissaoContext.atual();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("email", email);
        if (ctx == null) {
            // Não deveria acontecer se o filter rodou; retorna vazio defensivo.
            resp.put("cargo", "PROPRIETARIO");
            resp.put("permissoes", java.util.Arrays.stream(
                    com.mydelivery.equipe.Permissao.values()).map(Enum::name).sorted().toList());
            return ResponseEntity.ok(resp);
        }
        resp.put("membroId", ctx.membroId());
        resp.put("cargo", ctx.cargo() == null ? "PROPRIETARIO" : ctx.cargo().name());
        resp.put("login", ctx.login());
        resp.put("permissoes",
                PermissaoContext.permissoesEfetivas().stream().map(Enum::name).sorted().toList());
        return ResponseEntity.ok(resp);
    }
}