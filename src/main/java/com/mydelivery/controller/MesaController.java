package com.mydelivery.controller;

import java.text.Normalizer;
import java.util.HashMap;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.model.Mesa;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.MesaRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.PedidoService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gerenciamento de mesas (QR Codes presenciais) — admin do restaurante e
 * leitura pública pra validação ao escanear o QR.
 *
 * O QR não precisa ser persistido no servidor — o link é determinístico
 * (slug-restaurante + slug-mesa). Cliente do qrcode.js no painel gera a imagem.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MesaController {

    private final MesaRepository mesaRepo;
    private final RestauranteRepository restauranteRepo;
    private final PedidoService pedidoService;

    /** Lista as mesas do restaurante logado. */
    @GetMapping("/api/mesas")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> listar(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(mesaRepo.findByRestauranteIdOrderByNomeAsc(r.getId()).stream()
                .map(this::serializar)
                .toList());
    }

    /** Cria mesa. Body: { nome: "Mesa 01" }. Slug é gerado a partir do nome. */
    @PostMapping("/api/mesas")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> criar(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {

        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        String nome = body == null ? null : body.get("nome");
        if (nome == null || nome.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome da mesa é obrigatório");
        }
        nome = nome.trim();
        // Slug único por restaurante. Se colidir, anexa -2, -3...
        String base = slugify(nome);
        String slug = base;
        int n = 2;
        while (mesaRepo.existsByRestauranteIdAndSlug(r.getId(), slug)) {
            slug = base + "-" + n++;
            if (n > 999) throw new ResponseStatusException(HttpStatus.CONFLICT, "Muitas mesas com esse nome.");
        }
        Mesa m = mesaRepo.save(Mesa.builder()
                .restaurante(r)
                .nome(nome)
                .slug(slug)
                .ativa(true)
                .build());
        return ResponseEntity.status(HttpStatus.CREATED).body(serializar(m));
    }

    /**
     * Fecha a comanda da mesa: todos os pedidos ativos viram ENTREGUE + pago.
     * Equivalente ao "fechar conta" no balcão. Não altera mesa em si.
     */
    @PostMapping("/api/mesas/{id}/fechar-comanda")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> fecharComanda(
            @AuthenticationPrincipal String email,
            @PathVariable Long id) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        int fechados = pedidoService.fecharComandaMesa(r.getId(), id);
        return ResponseEntity.ok(Map.of("ok", true, "pedidosFechados", fechados));
    }

    /** Remove mesa (não apaga pedidos passados — apenas remove o QR ativo). */
    @DeleteMapping("/api/mesas/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Void> remover(@AuthenticationPrincipal String email, @PathVariable Long id) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        Mesa m = mesaRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mesa não encontrada"));
        if (!m.getRestaurante().getId().equals(r.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        mesaRepo.delete(m);
        return ResponseEntity.noContent().build();
    }

    /**
     * Endpoint PÚBLICO consultado quando o cliente escaneia o QR.
     * Confirma que a mesa existe pro restaurante e devolve o nome.
     *
     * Resposta: { valida, nome, slug } ou 404.
     */
    @GetMapping("/public/restaurante/{slugRest}/mesa/{slugMesa}")
    public ResponseEntity<Map<String, Object>> consultarPublica(
            @PathVariable String slugRest,
            @PathVariable String slugMesa) {

        Mesa m = mesaRepo.findByRestauranteSlugAndSlug(slugRest, slugMesa)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mesa não encontrada"));

        if (!Boolean.TRUE.equals(m.getAtiva())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Mesa desativada");
        }

        Map<String, Object> out = new HashMap<>();
        out.put("valida", true);
        out.put("nome", m.getNome());
        out.put("slug", m.getSlug());
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> serializar(Mesa m) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", m.getId());
        out.put("nome", m.getNome());
        out.put("slug", m.getSlug());
        out.put("ativa", m.getAtiva());
        out.put("criadaEm", m.getCriadaEm() != null ? m.getCriadaEm().toString() : null);
        return out;
    }

    /** kebab-case sem acento. "Área Externa" → "area-externa". */
    private static String slugify(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        n = n.toLowerCase().trim();
        n = n.replaceAll("[^a-z0-9\\s-]", "");
        n = n.replaceAll("[\\s-]+", "-");
        n = n.replaceAll("^-|-$", "");
        return n.isEmpty() ? "mesa" : n;
    }
}
