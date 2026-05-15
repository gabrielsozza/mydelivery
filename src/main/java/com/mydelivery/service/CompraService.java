package com.mydelivery.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.estoque.CompraDTO;
import com.mydelivery.model.Compra;
import com.mydelivery.model.CompraItem;
import com.mydelivery.model.Insumo;
import com.mydelivery.model.MovimentacaoEstoque;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.CompraRepository;
import com.mydelivery.repository.InsumoRepository;
import com.mydelivery.repository.MovimentacaoEstoqueRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompraService {

    private final CompraRepository compraRepository;
    private final InsumoRepository insumoRepository;
    private final MovimentacaoEstoqueRepository movRepository;
    private final RestauranteRepository restauranteRepository;

    /**
     * readOnly mantém a sessão JPA aberta durante a iteração — necessário porque
     * a coleção `itens` da Compra é LAZY por default e seria acessada ao construir
     * o DTO. Sem isso, lança LazyInitializationException → 400 no front.
     */
    @Transactional(readOnly = true)
    public List<CompraDTO> listarPorRestaurante(Long restauranteId) {
        return compraRepository.findByRestauranteIdOrderByDataCompraDesc(restauranteId)
                .stream().map(CompraDTO::fromEntity).collect(Collectors.toList());
    }

    /**
     * Registra uma compra. Pra cada item:
     *  1) Cria CompraItem
     *  2) Atualiza saldoAtual do insumo (+quantidade)
     *  3) Recalcula custoMedio do insumo (custo médio ponderado)
     *  4) Cria MovimentacaoEstoque do tipo ENTRADA_COMPRA
     *
     * Custo médio ponderado:
     *   novo_custo = (saldoAtual × custoMedioAtual + qtdComprada × custoCompra)
     *                / (saldoAtual + qtdComprada)
     *
     * Se ainda não havia custoMedio, usa o custo da compra direto.
     */
    @Transactional
    public CompraDTO criar(Long restauranteId, CompraDTO dto) {
        Restaurante r = restauranteRepository.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));

        if (dto.getItens() == null || dto.getItens().isEmpty())
            throw new RuntimeException("Informe ao menos um item na compra");

        // 1) Salva a Compra primeiro (sem itens) pra obter o ID — evita conflitos
        //    de FK quando o JPA tenta cascatear itens com referência circular.
        Compra c = Compra.builder()
                .restaurante(r)
                .fornecedor(dto.getFornecedor())
                .notaFiscal(dto.getNotaFiscal())
                .dataCompra(dto.getDataCompra() != null ? dto.getDataCompra() : LocalDateTime.now())
                .observacao(dto.getObservacao())
                .total(BigDecimal.ZERO)
                .itens(new ArrayList<>())
                .build();
        c = compraRepository.save(c);

        BigDecimal totalCompra = BigDecimal.ZERO;

        // 2) Pra cada item válido: cria CompraItem (com Compra já persistida),
        //    atualiza saldo+custo médio do insumo e registra movimentação.
        for (CompraDTO.Item itDto : dto.getItens()) {
            if (itDto.getInsumoId() == null || itDto.getQuantidade() == null
                    || itDto.getQuantidade().signum() <= 0) continue;

            Insumo ins = insumoRepository.findById(itDto.getInsumoId())
                    .orElseThrow(() -> new RuntimeException("Insumo não encontrado: id=" + itDto.getInsumoId()));
            if (ins.getRestaurante() == null || !ins.getRestaurante().getId().equals(restauranteId))
                throw new RuntimeException("Insumo '" + ins.getNome() + "' não pertence a este restaurante");

            BigDecimal qtd = itDto.getQuantidade();
            BigDecimal custoUnit = itDto.getCustoUnitario() != null ? itDto.getCustoUnitario() : BigDecimal.ZERO;
            BigDecimal subtotal = qtd.multiply(custoUnit).setScale(2, RoundingMode.HALF_UP);

            CompraItem ci = CompraItem.builder()
                    .compra(c)
                    .insumo(ins)
                    .quantidade(qtd)
                    .custoUnitario(custoUnit)
                    .subtotal(subtotal)
                    .build();
            c.getItens().add(ci);
            totalCompra = totalCompra.add(subtotal);

            // ── Atualiza custo médio ponderado ─────────────────────────────
            BigDecimal saldoAntes = ins.getSaldoAtual() != null ? ins.getSaldoAtual() : BigDecimal.ZERO;
            BigDecimal custoAtual = ins.getCustoMedio() != null ? ins.getCustoMedio() : BigDecimal.ZERO;
            BigDecimal saldoDepois = saldoAntes.add(qtd);
            BigDecimal novoCusto;
            if (saldoAntes.signum() <= 0 || custoAtual.signum() <= 0) {
                novoCusto = custoUnit;
            } else if (saldoDepois.signum() > 0) {
                BigDecimal totalValorAntes = saldoAntes.multiply(custoAtual);
                BigDecimal totalValorNovo = qtd.multiply(custoUnit);
                novoCusto = totalValorAntes.add(totalValorNovo)
                        .divide(saldoDepois, 4, RoundingMode.HALF_UP);
            } else {
                novoCusto = custoUnit;
            }
            ins.setSaldoAtual(saldoDepois);
            ins.setCustoMedio(novoCusto);
            insumoRepository.save(ins);

            // ── Registra a movimentação (já com compraId — agora temos o ID) ──
            MovimentacaoEstoque mov = MovimentacaoEstoque.builder()
                    .insumo(ins)
                    .tipo(MovimentacaoEstoque.Tipo.ENTRADA_COMPRA)
                    .quantidade(qtd)
                    .saldoApos(saldoDepois)
                    .compraId(c.getId())
                    .observacao("Compra" + (dto.getFornecedor() != null ? " — " + dto.getFornecedor() : ""))
                    .build();
            movRepository.save(mov);
        }

        // 3) Atualiza o total e re-salva (Hibernate persiste os itens via cascade)
        c.setTotal(totalCompra);
        Compra salvo = compraRepository.save(c);

        log.info("✅ Compra registrada: id={}, {} itens, total R$ {}",
                salvo.getId(), salvo.getItens().size(), totalCompra);

        return CompraDTO.fromEntity(salvo);
    }
}
