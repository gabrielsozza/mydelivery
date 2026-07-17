package com.mydelivery.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.ifood.IfoodMerchantClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Endpoints do módulo Merchant do iFood — expostos pro painel do dono.
 *
 * <p>3 cenários homologados:
 * <ol>
 *   <li>Informações da loja: listar, detalhar, consultar disponibilidade.</li>
 *   <li>Interrupções: criar pausa, listar, remover.</li>
 *   <li>Horários: consultar, atualizar (múltiplas janelas por dia).</li>
 * </ol>
 *
 * <p>Regra de segurança: cada dono só acessa a própria loja. O merchantId
 * é derivado do restaurante autenticado (não vem no path), garantindo que
 * um dono não consiga bisbilhotar/alterar loja de outro.
 *
 * <p>Se {@code ifoodMerchantId} não está preenchido (dono ainda não autorizou
 * a integração), todos os endpoints retornam 409 CONFLICT com mensagem
 * explicativa. UI deve orientar o dono a completar o setup primeiro.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class IfoodMerchantController {

    private final RestauranteRepository restauranteRepo;
    private final IfoodMerchantClient merchantClient;

    // ═══════════════════════════════════════════════════════════════════
    // Cenário 1: Informações
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Lista todos os merchants vinculados à conta do MyDelivery no iFood.
     * Homologação: mostra tudo que o Team ID enxerga.
     */
    @GetMapping("/api/restaurante/ifood/merchants")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> listar(
            @AuthenticationPrincipal String email) {
        exigirIntegracaoAtiva(email);
        return ResponseEntity.ok(merchantClient.listarMerchants());
    }

    /** Detalhes completos da loja (endereço, telefones, ticket médio, etc). */
    @GetMapping("/api/restaurante/ifood/merchant/detalhes")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> detalhes(
            @AuthenticationPrincipal String email) {
        String merchantId = merchantIdDoDono(email);
        return ResponseEntity.ok(merchantClient.detalhesMerchant(merchantId));
    }

    /** Disponibilidade da loja (aberta/fechada/pausada). */
    @GetMapping("/api/restaurante/ifood/merchant/status")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> status(
            @AuthenticationPrincipal String email) {
        String merchantId = merchantIdDoDono(email);
        return ResponseEntity.ok(merchantClient.statusMerchant(merchantId));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Cenário 2: Interrupções (pausas)
    // ═══════════════════════════════════════════════════════════════════

    @GetMapping("/api/restaurante/ifood/merchant/interrupcoes")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> listarInterrupcoes(
            @AuthenticationPrincipal String email) {
        String merchantId = merchantIdDoDono(email);
        return ResponseEntity.ok(merchantClient.listarInterrupcoes(merchantId));
    }

    /**
     * Cria pausa. Body: {@code { descricao, inicio, fim }} onde inicio/fim
     * são ISO 8601 com timezone offset (ex: "2026-07-16T18:00:00.000-03:00").
     */
    @PostMapping("/api/restaurante/ifood/merchant/interrupcoes")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> criarInterrupcao(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {
        String merchantId = merchantIdDoDono(email);
        String descricao = body.getOrDefault("descricao", "Pausa temporária");
        String inicio = body.get("inicio");
        String fim = body.get("fim");
        if (inicio == null || fim == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'inicio' e 'fim' são obrigatórios (formato ISO 8601 com timezone)");
        }
        var criada = merchantClient.criarInterrupcao(merchantId, descricao, inicio, fim);
        log.info("[iFood-Merchant] Dono {} criou pausa em {} de {} até {}",
                email, merchantId, inicio, fim);
        return ResponseEntity.ok(criada);
    }

    @DeleteMapping("/api/restaurante/ifood/merchant/interrupcoes/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> removerInterrupcao(
            @AuthenticationPrincipal String email,
            @PathVariable String id) {
        String merchantId = merchantIdDoDono(email);
        merchantClient.removerInterrupcao(merchantId, id);
        log.info("[iFood-Merchant] Dono {} removeu pausa {} em {}", email, id, merchantId);
        return ResponseEntity.ok(Map.of("ok", true, "removida", id));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Cenário 3: Horários
    // ═══════════════════════════════════════════════════════════════════

    @GetMapping("/api/restaurante/ifood/merchant/horarios")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> horarios(
            @AuthenticationPrincipal String email) {
        String merchantId = merchantIdDoDono(email);
        return ResponseEntity.ok(merchantClient.horariosMerchant(merchantId));
    }

    /**
     * Atualiza horários. Body: {@code { shifts: [ {dayOfWeek, start, duration} ] }}
     * onde duration em minutos e start em "HH:mm:ss".
     *
     * <p>Múltiplas janelas por dia = múltiplos itens no shifts com mesmo
     * dayOfWeek. Exemplo homologação: domingo 09-12h + 13-16h + 17-23h vira
     * 3 shifts com dayOfWeek=SUNDAY.
     */
    @PutMapping("/api/restaurante/ifood/merchant/horarios")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> atualizarHorarios(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        String merchantId = merchantIdDoDono(email);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shifts = (List<Map<String, Object>>) body.get("shifts");
        if (shifts == null || shifts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'shifts' vazio — envie ao menos 1 janela por dia");
        }
        merchantClient.atualizarHorarios(merchantId, shifts);
        log.info("[iFood-Merchant] Dono {} atualizou horários em {}: {} shifts",
                email, merchantId, shifts.size());
        return ResponseEntity.ok(Map.of("ok", true, "quantidade", shifts.size()));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

    private Restaurante restauranteDoDono(String email) {
        return restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Restaurante não encontrado"));
    }

    private String merchantIdDoDono(String email) {
        Restaurante r = restauranteDoDono(email);
        String id = r.getIfoodMerchantId();
        if (id == null || id.isBlank()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("erro", "integração iFood não configurada");
            m.put("mensagem", "Autorize a integração iFood em Configurações antes de usar este recurso.");
            throw new ResponseStatusException(HttpStatus.CONFLICT, m.toString());
        }
        return id;
    }

    /** Bloqueia acesso pra listar merchants se integração não está ativa. */
    private void exigirIntegracaoAtiva(String email) {
        Restaurante r = restauranteDoDono(email);
        if (!Boolean.TRUE.equals(r.getIfoodIntegracaoAtiva())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Integração iFood não está ativa");
        }
    }
}
