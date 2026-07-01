package com.mydelivery.controller;

import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.security.JwtUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Endpoint interno chamado APENAS pelo myafiliados-api pra abrir uma sessão
 * demo do sistema pra um afiliado. Fluxo:
 *
 *   1. Afiliado clica "Abrir demo" no painel.
 *   2. myafiliados-api chama POST /api/afiliado/demo/token com X-Afiliados-Secret.
 *   3. Este controller acha o restaurante configurado em DEMO_RESTAURANTE_SLUG
 *      (env), gera JWT de 30min com claim {@code demo=true} e devolve URL
 *      pronta pro afiliado abrir em nova aba.
 *   4. Frontend do painel restaurante recebe o token via query string,
 *      salva no localStorage e navega normalmente. DemoModeFilter bloqueia
 *      mutações destrutivas (delete, WhatsApp send, pagamentos, etc).
 *
 * Segurança:
 *  - Autenticação: header X-Afiliados-Secret precisa bater com AFILIADOS_DEMO_SECRET
 *    (mesmo padrão dos outros endpoints -admin já existentes).
 *  - Não expõe dados reais: só devolve URL + token; nenhum dado do banco.
 *  - Token curto: 30min é suficiente pra demonstração e limita risco.
 */
@Slf4j
@RestController
@RequestMapping("/api/afiliado/demo")
@RequiredArgsConstructor
public class DemoAcessoController {

    private final RestauranteRepository restauranteRepository;
    private final JwtUtil jwtUtil;

    /** Slug do restaurante seed usado como demo. Configurado via env. */
    @Value("${demo.restaurante.slug:mydelivery-demo}")
    private String demoSlug;

    /** URL do painel do restaurante (pra construir o link final). */
    @Value("${demo.painel.url:https://mydeliveryfood.com.br}")
    private String painelUrl;

    /** Secret que o myafiliados-api tem que enviar. */
    @Value("${afiliados.demo.secret:${AFILIADOS_DEMO_SECRET:}}")
    private String secret;

    /**
     * Retorna URL pronta pro afiliado abrir em nova aba, já com token temporário.
     * Body opcional: {"afiliadoId":"...","afiliadoEmail":"..."} — vira claim
     * demoAfiliado pra auditoria.
     */
    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> gerarTokenDemo(
            @RequestHeader(value = "X-Afiliados-Secret", required = false) String secretHeader,
            @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, Object> body) {

        // Bloqueio se secret não configurado (segurança) ou header não bater
        if (secret == null || secret.isBlank()) {
            log.error("DEMO_ACESSO: AFILIADOS_DEMO_SECRET não configurado no mydelivery-api");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("erro", "Demo não configurado"));
        }
        if (secretHeader == null || !secret.equals(secretHeader)) {
            log.warn("DEMO_ACESSO: chamada rejeitada — X-Afiliados-Secret ausente ou inválido");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("erro", "Não autorizado"));
        }

        // Busca restaurante demo pelo slug configurado.
        // Se não existir ainda, mensagem clara pro admin criar.
        Optional<Restaurante> demoOpt = restauranteRepository.findBySlug(demoSlug);
        if (demoOpt.isEmpty()) {
            log.warn("DEMO_ACESSO: restaurante demo com slug '{}' não encontrado. Crie um restaurante " +
                    "seed no admin com esse slug ou ajuste DEMO_RESTAURANTE_SLUG.", demoSlug);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("erro", "Demo ainda não configurado — em breve"));
        }
        Restaurante demo = demoOpt.get();
        if (demo.getUsuario() == null || demo.getUsuario().getEmail() == null) {
            log.error("DEMO_ACESSO: restaurante demo '{}' sem usuário associado", demoSlug);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("erro", "Demo mal configurado"));
        }

        // Identifica o afiliado pra auditoria (não interrompe se vier vazio)
        String afiliadoId = "";
        if (body != null) {
            Object id = body.getOrDefault("afiliadoId", body.get("afiliadoEmail"));
            if (id != null) afiliadoId = String.valueOf(id);
        }

        String token = jwtUtil.gerarTokenDemo(demo.getUsuario().getEmail(), afiliadoId);
        String url = painelUrl + "/pedidos.html?demo-token=" + token;

        log.info("DEMO_ACESSO: token gerado pra afiliado='{}' → slug='{}' (expira em 30min)",
                afiliadoId, demoSlug);
        return ResponseEntity.ok(Map.of(
                "url", url,
                "expiraEmMinutos", 30,
                "slug", demoSlug
        ));
    }
}
