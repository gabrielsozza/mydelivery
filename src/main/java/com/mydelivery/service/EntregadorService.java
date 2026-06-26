package com.mydelivery.service;

import com.mydelivery.dto.entregador.*;
import com.mydelivery.model.Entregador;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.EntregadorRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EntregadorService {

    /**
     * Status considerados "em andamento" pra contagem do entregador. Inclui
     * SAIU_ENTREGA porque é justamente quando ele está rodando pra entregar.
     */
    private static final Set<Pedido.Status> EM_ANDAMENTO = EnumSet.of(
            Pedido.Status.CONFIRMADO, Pedido.Status.EM_PREPARO,
            Pedido.Status.PRONTO, Pedido.Status.SAIU_ENTREGA);

    private final EntregadorRepository entregadorRepository;
    private final RestauranteRepository restauranteRepository;
    private final PedidoRepository pedidoRepository;
    private final EntregadorAuthService entregadorAuthService;

    @Transactional
    public EntregadorResponse criar(Long restauranteId, NovoEntregadorRequest req) {
        Restaurante restaurante = restauranteRepository.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante nao encontrado"));
        Entregador e = new Entregador();
        e.setRestaurante(restaurante);
        e.setNome(req.getNome());
        e.setTelefone(req.getTelefone());
        e.setVeiculo(req.getVeiculo());
        e.setPlaca(req.getPlaca());
        // PIN gerado automaticamente ao criar — restaurante vê no painel
        // e compartilha com o entregador (via WhatsApp/voz). Pode regerar
        // depois pelo endpoint /pin/regenerar.
        e.setPin(entregadorAuthService.gerarPinUnico(restauranteId));
        return toResponse(entregadorRepository.save(e), 0);
    }

    @Transactional(readOnly = true)
    public List<EntregadorResponse> listar(Long restauranteId) {
        List<Entregador> ativos = entregadorRepository.findByRestauranteIdAndAtivoTrue(restauranteId);
        // Conta pedidos em andamento por entregador num único pass (evita N+1).
        // Pega todos pedidos ativos do restaurante uma vez e agrupa em memória.
        Map<Long, Long> emAndamentoPorEntregador = pedidoRepository
                .findByRestauranteIdOrderByCriadoEmDesc(restauranteId).stream()
                .filter(p -> p.getEntregador() != null && EM_ANDAMENTO.contains(p.getStatus()))
                .collect(Collectors.groupingBy(p -> p.getEntregador().getId(), Collectors.counting()));
        return ativos.stream()
                .map(e -> toResponse(e, emAndamentoPorEntregador.getOrDefault(e.getId(), 0L).intValue()))
                .toList();
    }

    @Transactional
    public EntregadorResponse editar(Long restauranteId, Long id, NovoEntregadorRequest req) {
        Entregador e = buscar(restauranteId, id);
        e.setNome(req.getNome());
        e.setTelefone(req.getTelefone());
        e.setVeiculo(req.getVeiculo());
        e.setPlaca(req.getPlaca());
        return toResponse(entregadorRepository.save(e), contarEmAndamento(id));
    }

    @Transactional
    public EntregadorResponse atualizarStatus(Long restauranteId, Long id, AtualizarStatusEntregadorRequest req) {
        Entregador e = buscar(restauranteId, id);
        e.setStatus(req.getStatus());
        return toResponse(entregadorRepository.save(e), contarEmAndamento(id));
    }

    @Transactional
    public void desativar(Long restauranteId, Long id) {
        Entregador e = buscar(restauranteId, id);
        e.setAtivo(false);
        e.setOnline(false);
        entregadorRepository.save(e);
    }

    /**
     * Regera o PIN do entregador. Caso de uso: PIN comprometido (entregador
     * sair, número que sabia o PIN foi vazado, ou simples conveniência).
     * Não invalida sessões JWT já emitidas — o JWT segue válido até expirar
     * porque o subject é o entregadorId, não o PIN. Pra forçar logout
     * imediato precisa rotacionar JWT secret ou adicionar lista de revogação
     * (fora de escopo dessa fase).
     */
    @Transactional
    public EntregadorResponse regerarPin(Long restauranteId, Long id) {
        Entregador e = buscar(restauranteId, id);
        e.setPin(entregadorAuthService.gerarPinUnico(restauranteId));
        return toResponse(entregadorRepository.save(e), contarEmAndamento(id));
    }

    public Entregador buscar(Long restauranteId, Long id) {
        Entregador e = entregadorRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Entregador nao encontrado"));
        if (!e.getRestaurante().getId().equals(restauranteId))
            throw new RuntimeException("Acesso negado");
        return e;
    }

    private int contarEmAndamento(Long entregadorId) {
        // Path raro (1 entregador editado) — query mais leve que reusar listar()
        Entregador e = entregadorRepository.findById(entregadorId).orElse(null);
        if (e == null) return 0;
        return (int) pedidoRepository
                .findByRestauranteIdOrderByCriadoEmDesc(e.getRestaurante().getId()).stream()
                .filter(p -> p.getEntregador() != null
                        && p.getEntregador().getId().equals(entregadorId)
                        && EM_ANDAMENTO.contains(p.getStatus()))
                .count();
    }

    private EntregadorResponse toResponse(Entregador e, int pedidosEmAndamento) {
        return EntregadorResponse.builder()
                .id(e.getId()).nome(e.getNome()).telefone(e.getTelefone())
                .veiculo(e.getVeiculo()).placa(e.getPlaca())
                .status(e.getStatus()).ativo(e.getAtivo()).criadoEm(e.getCriadoEm())
                .pin(e.getPin())
                .online(Boolean.TRUE.equals(e.getOnline()))
                .ultimoLoginEm(e.getUltimoLoginEm())
                .pedidosEmAndamento(pedidosEmAndamento)
                .build();
    }
}
