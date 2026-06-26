package com.mydelivery.service;

import com.mydelivery.model.Entregador;
import com.mydelivery.repository.EntregadorRepository;
import com.mydelivery.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Autenticação do entregador no app mobile.
 *
 * Modelo idêntico ao do garçom (PIN multi-tenant + JWT com subject sintético),
 * porém:
 *  - PIN é armazenado em texto plano (campo Entregador.pin) pra suportar
 *    "Mostrar PIN" no painel do dono. Risco aceito: credencial operacional
 *    curta, escopada por slug do restaurante, só dá acesso aos pedidos
 *    atribuídos ao próprio entregador.
 *  - Subject do JWT: "entregador:{entregadorId}:{restauranteId}" — evita
 *    nova query no PedidoService/Controller pra recuperar contexto.
 *  - Role: "ENTREGADOR".
 */
@Service
@RequiredArgsConstructor
public class EntregadorAuthService {

    private static final SecureRandom RNG = new SecureRandom();

    private final EntregadorRepository entregadorRepository;
    private final JwtUtil jwtUtil;

    /**
     * Tenta autenticar entregador. Marca online=true e atualiza ultimoLoginEm
     * só em caso de sucesso. Retorna Optional vazio em PIN errado, restaurante
     * inválido, entregador inativo ou PIN nulo/vazio — qualquer falha vira
     * 401 genérico no controller (não vaza qual razão).
     */
    @Transactional
    public Optional<Entregador> autenticar(Long restauranteId, String pin) {
        if (restauranteId == null) return Optional.empty();
        if (pin == null || pin.isBlank()) return Optional.empty();
        Optional<Entregador> opt = entregadorRepository
                .findByRestauranteIdAndPinAndAtivoTrue(restauranteId, pin.trim());
        opt.ifPresent(e -> {
            e.setOnline(true);
            e.setUltimoLoginEm(LocalDateTime.now());
            entregadorRepository.save(e);
        });
        return opt;
    }

    /** Gera JWT no formato esperado pelo JwtAuthenticationFilter (subject + role). */
    public String gerarToken(Entregador entregador) {
        String subject = "entregador:" + entregador.getId() + ":"
                + entregador.getRestaurante().getId();
        return jwtUtil.gerarToken(subject, "ENTREGADOR");
    }

    /**
     * Gera PIN aleatório 4 dígitos único dentro do restaurante.
     * Limita 50 tentativas (4 dígitos = 10000 combinações, colisão é rara
     * mesmo com 100+ entregadores na mesma loja). Se estourar, joga exceção
     * pra o caller pedir intervenção manual.
     */
    public String gerarPinUnico(Long restauranteId) {
        for (int i = 0; i < 50; i++) {
            String candidato = String.format("%04d", RNG.nextInt(10000));
            if (!entregadorRepository.existsByRestauranteIdAndPin(restauranteId, candidato)) {
                return candidato;
            }
        }
        throw new IllegalStateException("Não foi possível gerar PIN único (50 tentativas)");
    }

    /** Marca entregador como offline (chamado em logout explícito). */
    @Transactional
    public void marcarOffline(Long entregadorId) {
        entregadorRepository.findById(entregadorId).ifPresent(e -> {
            e.setOnline(false);
            entregadorRepository.save(e);
        });
    }
}
