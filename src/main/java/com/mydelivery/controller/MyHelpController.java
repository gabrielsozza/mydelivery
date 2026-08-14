package com.mydelivery.controller;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.myhelp.MyHelpService;

import lombok.RequiredArgsConstructor;

/**
 * myHelp — assistente do dono. Liberado só pras lojas do beta (property
 * {@code myhelp.beta-slugs}). Todas as ações que mexem em dinheiro (preço de
 * produto, taxa de bairro) só aplicam via {@code /confirmar} depois do card.
 */
@RestController
@RequestMapping("/api/restaurante/myhelp")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('RESTAURANTE', 'ADMIN')")
public class MyHelpController {

    private final MyHelpService myHelp;
    private final RestauranteRepository restauranteRepo;

    /** O front usa isso pra decidir se mostra o balão (só no beta). */
    @GetMapping("/habilitado")
    public ResponseEntity<Map<String, Object>> habilitado(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElse(null);
        boolean on = r != null && myHelp.habilitado(r);
        return ResponseEntity.ok(Map.of("habilitado", on));
    }

    @PostMapping("/mensagem")
    public ResponseEntity<Map<String, Object>> mensagem(@AuthenticationPrincipal String email,
                                                         @RequestBody Map<String, Object> body) {
        Restaurante r = getRestaurante(email);
        if (!myHelp.habilitado(r)) return ResponseEntity.ok(Map.of("tipo", "texto", "mensagem", "Recurso indisponível."));
        String texto = body.get("texto") == null ? "" : body.get("texto").toString();
        return ResponseEntity.ok(myHelp.responder(r, texto));
    }

    @PostMapping("/confirmar")
    public ResponseEntity<Map<String, Object>> confirmar(@AuthenticationPrincipal String email,
                                                         @RequestBody Map<String, Object> body) {
        Restaurante r = getRestaurante(email);
        if (!myHelp.habilitado(r)) return ResponseEntity.ok(Map.of("tipo", "texto", "mensagem", "Recurso indisponível."));
        String acao = str(body.get("acao"));
        if ("taxaBairro".equals(acao)) {
            return ResponseEntity.ok(myHelp.confirmarTaxaBairro(r, str(body.get("bairroNome")), dec(body.get("taxaNova"))));
        }
        // padrão: preço de produto
        Long produtoId = body.get("produtoId") == null ? null : Long.valueOf(body.get("produtoId").toString());
        return ResponseEntity.ok(myHelp.confirmarPreco(r, produtoId, dec(body.get("precoNovo"))));
    }

    private Restaurante getRestaurante(String email) {
        return restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
    }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static BigDecimal dec(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(o.toString().replace(",", ".")); } catch (Exception e) { return null; }
    }
}
