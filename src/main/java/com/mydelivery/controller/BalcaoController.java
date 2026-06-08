package com.mydelivery.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.model.Pedido;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.repository.SenhaBalcaoRepository;
import com.mydelivery.service.BalcaoService;

import lombok.RequiredArgsConstructor;

/**
 * POS de balcão — usado pelo caixa (autenticado como RESTAURANTE).
 *
 *  POST  /api/restaurante/balcao/pedido            cria pedido + gera senha
 *  GET   /api/restaurante/balcao/fila              lista pedidos do dia em preparo
 *  PATCH /api/restaurante/balcao/pedido/{id}       muda status (PREPARO/PRONTO/ENTREGUE)
 *  GET   /api/restaurante/balcao/cliente?tel=X     memória do cliente (top 5 itens)
 */
@RestController
@RequiredArgsConstructor
public class BalcaoController {

    private final BalcaoService balcaoService;
    private final RestauranteRepository restauranteRepo;
    private final PedidoRepository pedidoRepo;
    private final SenhaBalcaoRepository senhaRepo;

    @PostMapping("/api/restaurante/balcao/pedido")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> criar(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itens = (List<Map<String, Object>>) body.get("itens");
        try {
            var resp = balcaoService.criarPedido(
                    r,
                    strOf(body.get("nomeChamada")),
                    strOf(body.get("telefoneCliente")),
                    itens,
                    strOf(body.get("observacao")),
                    strOf(body.get("formaPagamento")));
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/api/restaurante/balcao/fila")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<List<Map<String, Object>>> fila(@AuthenticationPrincipal String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(balcaoService.fila(r.getId()));
    }

    @GetMapping("/api/restaurante/balcao/cliente")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> memoriaCliente(
            @AuthenticationPrincipal String email,
            @RequestParam(value = "tel", required = false) String tel) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        return ResponseEntity.ok(balcaoService.memoriaCliente(r.getId(), tel));
    }

    /**
     * Cobra um pedido balcao que foi criado como "Cobrar depois"
     * (formaPagamento=PENDENTE). Recebe a forma real escolhida no momento
     * do pagamento. Marca pago=true e grava timestamp.
     */
    @PostMapping("/api/restaurante/balcao/pedido/{id}/cobrar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> cobrar(
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        String forma = body == null ? null : body.get("formaPagamento");
        if (forma == null || forma.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "formaPagamento obrigatoria");
        }
        try {
            return ResponseEntity.ok(balcaoService.cobrar(r.getId(), id, forma));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    @PatchMapping("/api/restaurante/balcao/pedido/{id}")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> mudarStatus(
            @AuthenticationPrincipal String email,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        Pedido p = pedidoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!p.getRestaurante().getId().equals(r.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String novoStr = body == null ? null : body.get("status");
        if (novoStr == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status obrigatório");
        try {
            p.setStatus(Pedido.Status.valueOf(novoStr.toUpperCase()));
            if (p.getStatus() == Pedido.Status.ENTREGUE) {
                p.setPago(true);
                p.setPagoEm(java.time.LocalDateTime.now());
            }
            pedidoRepo.save(p);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status inválido");
        }
        return ResponseEntity.ok(Map.of("ok", true, "status", p.getStatus().name()));
    }

    /**
     * Endpoint PÚBLICO pro painel de chamada na TV do balcão. Sem auth porque
     * TV não tem teclado.
     *
     * <p>Mostra pedidos do dia onde o cliente está esperando ser CHAMADO
     * pra retirar:
     * <ul>
     *   <li>BALCAO (com senha numerica) — cliente pegou senha, espera
     *   <li>RETIRADA (cliente fez pedido pra buscar, espera ficar pronto)
     * </ul>
     * <b>MESA fica de fora</b> — cliente esta sentado, garcom leva direto.
     * Mostrar pedido de mesa na TV nao serve a ninguem. <b>DELIVERY</b>
     * idem — cliente nao esta no salao.
     *
     * <p>Status filtrados (excluidos): ENTREGUE, CANCELADO,
     * SAIU_ENTREGA (delivery), AGUARDANDO_PAGAMENTO. Os demais
     * (PENDENTE, CONFIRMADO, EM_PREPARO, PRONTO, NA_MESA) aparecem.
     */
    @GetMapping("/public/painel-chamada/{slugRestaurante}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> painelChamada(@PathVariable String slugRestaurante) {
        Restaurante r = restauranteRepo.findBySlug(slugRestaurante)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var hoje = java.time.LocalDate.now();

        // 1. Indexa as senhas do dia por pedidoId — usado pra mostrar #42 em
        //    pedidos BALCAO + dedup (nao mostra o mesmo pedido 2x se ja
        //    estiver na lista de senhas).
        var senhas = senhaRepo.findByRestauranteIdAndDataEmissaoOrderByNumeroAsc(r.getId(), hoje);
        java.util.Map<Long, com.mydelivery.model.SenhaBalcao> porPedidoId = new java.util.HashMap<>();
        for (var s : senhas) porPedidoId.put(s.getPedidoId(), s);

        // 2. Pedidos do dia (BALCAO/RETIRADA/MESA) ativos. DELIVERY fora.
        var inicio = hoje.atStartOfDay();
        var fim = hoje.plusDays(1).atStartOfDay();
        var pedidosHoje = pedidoRepo.findByRestauranteIdAndPeriodo(r.getId(), inicio, fim);

        java.util.List<Map<String, Object>> prontos = new java.util.ArrayList<>();
        java.util.List<Map<String, Object>> preparando = new java.util.ArrayList<>();
        for (Pedido p : pedidosHoje) {
            // So BALCAO + RETIRADA aparecem na TV. MESA = garcom leva.
            // DELIVERY = cliente nao esta no salao.
            Pedido.Tipo tp = p.getTipo();
            if (tp != Pedido.Tipo.BALCAO && tp != Pedido.Tipo.RETIRADA) continue;
            // Filtra status final: entregue, cancelado, saiu pra entrega,
            // aguardando pagamento online — nada disso aparece na TV.
            Pedido.Status st = p.getStatus();
            if (st == Pedido.Status.ENTREGUE
                || st == Pedido.Status.CANCELADO
                || st == Pedido.Status.SAIU_ENTREGA
                || st == Pedido.Status.AGUARDANDO_PAGAMENTO) continue;

            Map<String, Object> m = new java.util.LinkedHashMap<>();
            // Identificador: senha numerica (BALCAO) > nome cliente (RETIRADA).
            var senha = porPedidoId.get(p.getId());
            if (senha != null) {
                m.put("senha", senha.getNumero());
                m.put("nome", senha.getNomeCliente());
            } else {
                // RETIRADA (ou BALCAO antigo sem senha) — usa nome do
                // cliente cadastrado ou nomeChamada (fallback do toResponse).
                m.put("senha", null);
                String nome = p.getCliente() != null ? p.getCliente().getNome() : null;
                if (nome == null || nome.isBlank()) nome = p.getNomeChamada();
                if (nome == null || nome.isBlank()) nome = "Pedido #" + p.getId();
                m.put("nome", nome);
            }
            m.put("status", st.name());

            // PRONTO = chamar. NA_MESA nao se aplica (mesa nao entra na TV).
            if (st == Pedido.Status.PRONTO) {
                prontos.add(m);
            } else {
                preparando.add(m);
            }
        }

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("restaurante", r.getNome());
        out.put("prontos", prontos);
        out.put("preparando", preparando);
        return ResponseEntity.ok(out);
    }

    private String strOf(Object o) { return o == null ? null : o.toString(); }
}
