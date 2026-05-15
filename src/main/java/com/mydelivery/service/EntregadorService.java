package com.mydelivery.service;

import com.mydelivery.dto.entregador.*;
import com.mydelivery.model.Entregador;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.EntregadorRepository;
import com.mydelivery.repository.RestauranteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EntregadorService {

    private final EntregadorRepository entregadorRepository;
    private final RestauranteRepository restauranteRepository;

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
        return toResponse(entregadorRepository.save(e));
    }

    @Transactional(readOnly = true)
    public List<EntregadorResponse> listar(Long restauranteId) {
        return entregadorRepository.findByRestauranteIdAndAtivoTrue(restauranteId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public EntregadorResponse editar(Long restauranteId, Long id, NovoEntregadorRequest req) {
        Entregador e = buscar(restauranteId, id);
        e.setNome(req.getNome());
        e.setTelefone(req.getTelefone());
        e.setVeiculo(req.getVeiculo());
        e.setPlaca(req.getPlaca());
        return toResponse(entregadorRepository.save(e));
    }

    @Transactional
    public EntregadorResponse atualizarStatus(Long restauranteId, Long id, AtualizarStatusEntregadorRequest req) {
        Entregador e = buscar(restauranteId, id);
        e.setStatus(req.getStatus());
        return toResponse(entregadorRepository.save(e));
    }

    @Transactional
    public void desativar(Long restauranteId, Long id) {
        Entregador e = buscar(restauranteId, id);
        e.setAtivo(false);
        entregadorRepository.save(e);
    }

    public Entregador buscar(Long restauranteId, Long id) {
        Entregador e = entregadorRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Entregador nao encontrado"));
        if (!e.getRestaurante().getId().equals(restauranteId))
            throw new RuntimeException("Acesso negado");
        return e;
    }

    private EntregadorResponse toResponse(Entregador e) {
        return EntregadorResponse.builder()
                .id(e.getId()).nome(e.getNome()).telefone(e.getTelefone())
                .veiculo(e.getVeiculo()).placa(e.getPlaca())
                .status(e.getStatus()).ativo(e.getAtivo()).criadoEm(e.getCriadoEm())
                .build();
    }
}
