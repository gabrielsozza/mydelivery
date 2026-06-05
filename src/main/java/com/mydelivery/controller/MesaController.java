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
     * Sincroniza a quantidade total de mesas do restaurante. Body: {"quantidade":25}.
     *  - Se faltam mesas: cria "Mesa NN" (zero-padded em 2 dígitos) das que faltam.
     *  - Se sobra: NÃO apaga (preserva histórico de pedidos). O dono remove manualmente
     *    pelo botão X se quiser reduzir.
     * Idempotente: chamadas repetidas com mesmo número não criam duplicatas.
     */
    @PostMapping("/api/mesas/bulk")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> gerarMesasBulk(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        int qtd = 0;
        try { qtd = Integer.parseInt(String.valueOf(body.get("quantidade"))); } catch (Exception ignore) {}
        if (qtd <= 0 || qtd > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantidade inválida (1–500)");
        }
        int criadas = 0;
        for (int i = 1; i <= qtd; i++) {
            String nome = "Mesa " + String.format("%02d", i);
            String slug = "mesa-" + String.format("%02d", i);
            if (mesaRepo.existsByRestauranteIdAndSlug(r.getId(), slug)) continue;
            mesaRepo.save(Mesa.builder()
                    .restaurante(r)
                    .nome(nome)
                    .slug(slug)
                    .ativa(true)
                    .build());
            criadas++;
        }
        log.info("[Mesa] bulk restauranteId={} qtd={} criadas={}", r.getId(), qtd, criadas);
        return ResponseEntity.ok(Map.of("ok", true, "criadas", criadas, "total", qtd));
    }

    /**
     * Marca a comanda como PAGA sem fechar — pedidos continuam em andamento,
     * só sinaliza que o cliente já pagou. Botão complementar ao "Fechar conta".
     */
    @PostMapping("/api/mesas/{id}/marcar-pago")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> marcarPago(
            @AuthenticationPrincipal String email,
            @PathVariable Long id) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        int alterados = pedidoService.marcarComandaPaga(r.getId(), id);
        return ResponseEntity.ok(Map.of("ok", true, "pedidosAlterados", alterados));
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
        out.put("setor", m.getSetor());
        out.put("capacidade", m.getCapacidade());
        out.put("posicaoX", m.getPosicaoX());
        out.put("posicaoY", m.getPosicaoY());
        out.put("criadaEm", m.getCriadaEm() != null ? m.getCriadaEm().toString() : null);
        return out;
    }

    /**
     * Edita atributos da mesa: nome, setor, capacidade e posição no mapa.
     * Body parcial: { nome?, setor?, capacidade?, posicaoX?, posicaoY? }
     * Não-nulos atualizam, nulos preservam.
     */
    @org.springframework.web.bind.annotation.PatchMapping("/api/mesas/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> editar(
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        Mesa m = mesaRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!m.getRestaurante().getId().equals(r.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (body.get("nome") != null) {
            String nome = body.get("nome").toString().trim();
            if (!nome.isEmpty()) m.setNome(nome);
        }
        if (body.containsKey("setor")) {
            Object v = body.get("setor");
            m.setSetor(v == null ? null : v.toString().trim());
        }
        if (body.get("capacidade") instanceof Number n) {
            int cap = n.intValue();
            if (cap > 0 && cap <= 50) m.setCapacidade(cap);
        }
        if (body.containsKey("posicaoX")) {
            Object v = body.get("posicaoX");
            m.setPosicaoX(v instanceof Number n ? n.intValue() : null);
        }
        if (body.containsKey("posicaoY")) {
            Object v = body.get("posicaoY");
            m.setPosicaoY(v instanceof Number n ? n.intValue() : null);
        }
        mesaRepo.save(m);
        return ResponseEntity.ok(serializar(m));
    }

    /** Bulk-update de posições — payload [{id, posicaoX, posicaoY}, ...]. Usado pelo
     *  editor de mapa do salão: arrastou mesa, salva todas as posições em 1 chamada. */
    @org.springframework.web.bind.annotation.PatchMapping("/api/mesas/posicoes")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> atualizarPosicoes(
            @AuthenticationPrincipal String email,
            @RequestBody List<Map<String, Object>> posicoes) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        int alterados = 0;
        for (var p : posicoes) {
            Object idObj = p.get("id");
            if (!(idObj instanceof Number)) continue;
            Long mid = ((Number) idObj).longValue();
            var mOpt = mesaRepo.findById(mid);
            if (mOpt.isEmpty()) continue;
            Mesa m = mOpt.get();
            if (!m.getRestaurante().getId().equals(r.getId())) continue;
            if (p.get("posicaoX") instanceof Number nx) m.setPosicaoX(nx.intValue());
            if (p.get("posicaoY") instanceof Number ny) m.setPosicaoY(ny.intValue());
            mesaRepo.save(m);
            alterados++;
        }
        return ResponseEntity.ok(Map.of("ok", true, "alterados", alterados));
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
