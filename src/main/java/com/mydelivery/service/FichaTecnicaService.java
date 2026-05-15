package com.mydelivery.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.estoque.FichaTecnicaDTO;
import com.mydelivery.model.FichaTecnicaItem;
import com.mydelivery.model.Insumo;
import com.mydelivery.model.Produto;
import com.mydelivery.repository.FichaTecnicaItemRepository;
import com.mydelivery.repository.InsumoRepository;
import com.mydelivery.repository.ProdutoRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FichaTecnicaService {

    private final FichaTecnicaItemRepository fichaRepository;
    private final InsumoRepository insumoRepository;
    private final ProdutoRepository produtoRepository;

    public FichaTecnicaDTO obter(Long restauranteId, Long produtoId) {
        Produto p = produtoRepository.findById(produtoId)
                .orElseThrow(() -> new RuntimeException("Produto não encontrado"));
        if (!p.getRestaurante().getId().equals(restauranteId))
            throw new RuntimeException("Acesso negado");

        FichaTecnicaDTO dto = new FichaTecnicaDTO();
        dto.setProdutoId(p.getId());
        dto.setProdutoNome(p.getNome());

        List<FichaTecnicaDTO.Item> itens = new ArrayList<>();
        for (FichaTecnicaItem fi : fichaRepository.findByProdutoId(produtoId)) {
            FichaTecnicaDTO.Item it = new FichaTecnicaDTO.Item();
            it.setInsumoId(fi.getInsumo().getId());
            it.setInsumoNome(fi.getInsumo().getNome());
            it.setUnidade(fi.getInsumo().getUnidade() != null ? fi.getInsumo().getUnidade().name() : "UN");
            it.setQuantidade(fi.getQuantidade());
            it.setSaldoAtualInsumo(fi.getInsumo().getSaldoAtual());
            itens.add(it);
        }
        dto.setItens(itens);
        return dto;
    }

    /**
     * Substitui a ficha técnica completa de um produto. Deleta a antiga e cria a nova.
     * Simples e seguro (transacional).
     */
    @Transactional
    public FichaTecnicaDTO salvar(Long restauranteId, Long produtoId, FichaTecnicaDTO dto) {
        Produto p = produtoRepository.findById(produtoId)
                .orElseThrow(() -> new RuntimeException("Produto não encontrado"));
        if (!p.getRestaurante().getId().equals(restauranteId))
            throw new RuntimeException("Acesso negado");

        fichaRepository.deleteByProdutoId(produtoId);

        if (dto.getItens() != null) {
            for (FichaTecnicaDTO.Item it : dto.getItens()) {
                if (it.getInsumoId() == null || it.getQuantidade() == null || it.getQuantidade().signum() <= 0) continue;
                Insumo insumo = insumoRepository.findById(it.getInsumoId())
                        .orElseThrow(() -> new RuntimeException("Insumo não encontrado: " + it.getInsumoId()));
                if (!insumo.getRestaurante().getId().equals(restauranteId))
                    throw new RuntimeException("Insumo de outro restaurante");
                FichaTecnicaItem fi = FichaTecnicaItem.builder()
                        .produto(p)
                        .insumo(insumo)
                        .quantidade(it.getQuantidade())
                        .build();
                fichaRepository.save(fi);
            }
        }
        return obter(restauranteId, produtoId);
    }
}
