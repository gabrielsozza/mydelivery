package com.mydelivery.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}