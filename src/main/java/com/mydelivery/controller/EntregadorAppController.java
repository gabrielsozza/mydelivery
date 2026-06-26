package com.mydelivery.controller;

import com.mydelivery.dto.entregador.AtualizarStatusEntregadorPedidoRequest;
import com.mydelivery.dto.pedido.PedidoResponse;
import com.mydelivery.model.Entregador;
import com.mydelivery.model.Pedido;
import com.mydelivery.repository.EntregadorRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.service.EntregadorAuthService;
import com.mydelivery.service.PedidoService;
import com.mydelivery.dto.pedido.AtualizarStatusRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App mobile do entregador. Todos os endpoints exigem role ENTREGADOR
 * (enforce no SecurityConfig + @PreAuthorize por defesa em profundidade).
 *
 * Princípio: entregador só enxerga e mexe nos pedidos atribuídos a ele
 * mesmo, dentro do restaurante onde fez login. Subject do JWT carrega
 * "entregador:{entregadorId}:{restauranteId}" — parseado por parseCtx()
 * pra evitar query extra a cada request.
 */
@RestController
@RequestMapping("/api/entregador")
@RequiredArgsConstructor
public class EntregadorAppController {

    private final EntregadorRepository entregadorRepository;
    private final PedidoRepository pedidoRepository;
    private final PedidoService pedidoService;
    private final EntregadorAuthService entregadorAuthService;

    private record Ctx(Long entregadorId, Long restauranteId) {}

    /**
     * Parse subject. Formato esperado: "entregador:{eid}:{rid}".
     * Se vier qualquer outra coisa, 401 imediato — não confiamos em token
     * com formato fora do padrão (defesa contra token forjado/legado).
     */
    private Ctx parseCtx(String subject) {
        if (subject == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida");
        }
        String[] partes = subject.split(":");
        if (partes.length != 3 || !"entregador".equals(partes[0])) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida");
        }
        try {
            return new Ctx(Long.parseLong(partes[1]), Long.parseLong(partes[2]));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida");
        }
    }

    /** Perfil do entregador logado + dados do restaurante. App usa pra header. */
    @GetMapping("/me")
    @PreAuthorize("hasRole('ENTREGADOR')")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal String subject) {
        Ctx ctx = parseCtx(subject);
        Entregador e = entregadorRepository.findById(ctx.entregadorId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Entregador não encontrado"));
        if (!e.getRestaurante().getId().equals(ctx.restauranteId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida");
        }
        if (!Boolean.TRUE.equals(e.getAtivo())) {
            // Entregador foi desativado pelo dono — invalida sessão.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Conta desativada");
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", e.getId());
        resp.put("nome", e.getNome());
        resp.put("veiculo", e.getVeiculo());
        resp.put("placa", e.getPlaca());
        resp.put("status", e.getStatus());
        resp.put("online", Boolean.TRUE.equals(e.getOnline()));
        resp.put("restauranteId", e.getRestaurante().getId());
        resp.put("restauranteNome", e.getRestaurante().getNome());
        resp.put("restauranteSlug", e.getRestaurante().getSlug());
        return ResponseEntity.ok(resp);
    }

    /**
     * Pedidos atribuídos ao entregador. Por padrão devolve os ativos
     * (não ENTREGUE/CANCELADO). Inclui também CONFIRMADO/EM_PREPARO/
     * PRONTO/SAIU_ENTREGA — o app mobile decide o que mostrar (lista
     * "aguardando preparo" + "saiu pra entrega").
     */
    @GetMapping("/meus-pedidos")
    @PreAuthorize("hasRole('ENTREGADOR')")
    @Transactional(readOnly = true)
    public ResponseEntity<List<PedidoResponse>> meusPedidos(@AuthenticationPrincipal String subject) {
        Ctx ctx = parseCtx(subject);
        // Reutiliza o listarPedidos do PedidoService e filtra. Mais simples
        // que adicionar query custom — volume por entregador é baixo (≤ 20
        // pedidos ativos por dia tipicamente).
        List<PedidoResponse> todos = pedidoService.listarPedidos(ctx.restauranteId());
        List<PedidoResponse> filtrados = todos.stream()
                .filter(p -> p.getEntregadorId() != null
                        && p.getEntregadorId().equals(ctx.entregadorId())
                        && p.getStatus() != Pedido.Status.ENTREGUE
                        && p.getStatus() != Pedido.Status.CANCELADO)
                .toList();
        return ResponseEntity.ok(filtrados);
    }

    /**
     * Entregador atualiza status do pedido atribuído. Aceita só
     * SAIU_ENTREGA e ENTREGUE — não pode voltar status nem pular pra
     * CANCELADO (cancelamento é responsabilidade do dono).
     *
     * Validações:
     *  - pedido pertence ao restaurante do entregador
     *  - pedido está atribuído a esse entregador
     *  - status novo é permitido pra app
     *  - transição faz sentido (não pode marcar ENTREGUE antes de SAIU_ENTREGA
     *    em pedidos delivery — exceto retirada que pula direto)
     *
     * Reutiliza PedidoService.atualizarStatus pra herdar propagação
     * iFood + ENTREGUE auto-libera entregador (lá no service).
     */
    @PatchMapping("/pedido/{pedidoId}/status")
    @PreAuthorize("hasRole('ENTREGADOR')")
    public ResponseEntity<PedidoResponse> atualizarStatusPedido(
            @AuthenticationPrincipal String subject,
            @PathVariable Long pedidoId,
            @RequestBody AtualizarStatusEntregadorPedidoRequest req) {
        Ctx ctx = parseCtx(subject);
        if (req == null || req.getStatus() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status obrigatório");
        }
        // Allow-list de status que o app pode emitir.
        Pedido.Status novo = req.getStatus();
        if (novo != Pedido.Status.SAIU_ENTREGA && novo != Pedido.Status.ENTREGUE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Status não permitido pelo app");
        }
        Pedido p = pedidoRepository.findById(pedidoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
        if (!p.getRestaurante().getId().equals(ctx.restauranteId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Pedido de outro restaurante");
        }
        if (p.getEntregador() == null || !p.getEntregador().getId().equals(ctx.entregadorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Pedido não atribuído a você");
        }
        // Transição: marca ENTREGUE só vindo de SAIU_ENTREGA (delivery) ou
        // PRONTO (retirada — cliente pode ter pegado direto). Não bloqueia
        // demais combinações pra não engessar fluxo de exceção.
        AtualizarStatusRequest sr = new AtualizarStatusRequest();
        sr.setStatus(novo);
        PedidoResponse resp = pedidoService.atualizarStatus(ctx.restauranteId(), pedidoId, sr);

        // Após ENTREGUE, libera o entregador automaticamente (volta DISPONIVEL)
        // se não tem mais pedidos em andamento. Mantém EM_ENTREGA caso ainda
        // tenha outra entrega na rua.
        if (novo == Pedido.Status.ENTREGUE) {
            liberarEntregadorSeOcioso(ctx.entregadorId(), ctx.restauranteId());
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * Logout explícito do app — marca offline. Não invalida o JWT em si
     * (sem blocklist), mas o app descarta o token e o flag online some
     * imediato do painel admin.
     */
    @PostMapping("/logout")
    @PreAuthorize("hasRole('ENTREGADOR')")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal String subject) {
        Ctx ctx = parseCtx(subject);
        entregadorAuthService.marcarOffline(ctx.entregadorId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Após entregador finalizar 1 pedido, checa se ainda tem outros em
     * andamento. Se zero, volta status pra DISPONIVEL — fica disponível
     * pra próxima atribuição automática.
     */
    private void liberarEntregadorSeOcioso(Long entregadorId, Long restauranteId) {
        try {
            long aindaEmAndamento = pedidoRepository
                    .findByRestauranteIdOrderByCriadoEmDesc(restauranteId).stream()
                    .filter(p -> p.getEntregador() != null
                            && p.getEntregador().getId().equals(entregadorId)
                            && p.getStatus() != Pedido.Status.ENTREGUE
                            && p.getStatus() != Pedido.Status.CANCELADO)
                    .count();
            if (aindaEmAndamento == 0) {
                entregadorRepository.findById(entregadorId).ifPresent(e -> {
                    if (e.getStatus() == Entregador.Status.EM_ENTREGA) {
                        e.setStatus(Entregador.Status.DISPONIVEL);
                        entregadorRepository.save(e);
                    }
                });
            }
        } catch (Exception ignored) {
            // Fail-safe — não bloqueia a transição do pedido se cleanup falhar
        }
    }
}
