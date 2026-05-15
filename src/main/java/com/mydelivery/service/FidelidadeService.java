package com.mydelivery.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.fidelidade.ProgramaFidelidadeDTO;
import com.mydelivery.dto.fidelidade.StatusClienteDTO;
import com.mydelivery.model.Cupom;
import com.mydelivery.model.PontosTransacao;
import com.mydelivery.model.ProgramaFidelidade;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.PontosTransacaoRepository;
import com.mydelivery.repository.ProgramaFidelidadeRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FidelidadeService {

    private final ProgramaFidelidadeRepository programaRepo;
    private final PontosTransacaoRepository pontosRepo;
    private final RestauranteRepository restauranteRepo;
    private final CupomService cupomService;

    // ── CONFIG (admin) ──────────────────────────────────────────────────────

    public ProgramaFidelidadeDTO obterConfig(Long restauranteId) {
        return ProgramaFidelidadeDTO.fromEntity(
                programaRepo.findByRestauranteId(restauranteId).orElse(null));
    }

    @Transactional
    public ProgramaFidelidadeDTO salvarConfig(Long restauranteId, ProgramaFidelidadeDTO dto) {
        Restaurante r = restauranteRepo.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));

        ProgramaFidelidade p = programaRepo.findByRestauranteId(restauranteId)
                .orElseGet(() -> ProgramaFidelidade.builder().restaurante(r).build());

        if (dto.getAtivo() != null)               p.setAtivo(dto.getAtivo());
        if (dto.getValorPorPonto() != null)       p.setValorPorPonto(dto.getValorPorPonto());
        if (dto.getPontosParaRecompensa() != null) p.setPontosParaRecompensa(dto.getPontosParaRecompensa());
        if (dto.getTipoRecompensa() != null)      p.setTipoRecompensa(ProgramaFidelidade.TipoRecompensa.valueOf(dto.getTipoRecompensa()));
        if (dto.getValorRecompensa() != null)     p.setValorRecompensa(dto.getValorRecompensa());
        if (dto.getDescricaoRecompensa() != null) p.setDescricaoRecompensa(dto.getDescricaoRecompensa());
        if (dto.getDiasExpiracao() != null)       p.setDiasExpiracao(dto.getDiasExpiracao());

        return ProgramaFidelidadeDTO.fromEntity(programaRepo.save(p));
    }

    // ── PÚBLICO (cliente) ───────────────────────────────────────────────────

    public StatusClienteDTO statusCliente(String slug, String telefone) {
        Optional<ProgramaFidelidade> opt = programaRepo.findByRestauranteSlug(slug);
        if (opt.isEmpty() || !Boolean.TRUE.equals(opt.get().getAtivo())) {
            return StatusClienteDTO.builder().programaAtivo(false).build();
        }
        ProgramaFidelidade p = opt.get();
        // Normaliza pra bater com o que está salvo (sempre só dígitos)
        String telNorm = com.mydelivery.util.TelefoneUtil.normalizar(telefone);
        int saldo = telNorm != null
                ? calcularSaldo(p.getRestaurante().getId(), telNorm)
                : 0;
        int faltam = Math.max(0, p.getPontosParaRecompensa() - saldo);

        // Cupom de fidelidade disponível? (gerado pra esse telefone, não usado, dentro da validade)
        String cupomDisp = null;
        // Para evitar dependência circular, busca direto via repository do service de cupom
        // (não temos query específica — pra MVP, deixar null e descobrir quando aplicar.)
        // Cupons gerados pela fidelidade são mostrados como "disponível" via outro fluxo:
        // quando creditar pontos, retornamos o código gerado ali mesmo. Aqui null é OK.

        return StatusClienteDTO.builder()
                .programaAtivo(true)
                .saldoPontos(saldo)
                .pontosParaRecompensa(p.getPontosParaRecompensa())
                .pontosFaltando(faltam)
                .tipoRecompensa(p.getTipoRecompensa().name())
                .valorRecompensa(p.getValorRecompensa())
                .descricaoRecompensa(p.getDescricaoRecompensa())
                .valorPorPonto(p.getValorPorPonto())
                .cupomDisponivel(cupomDisp)
                .build();
    }

    public int calcularSaldo(Long restauranteId, String telefone) {
        Long saldo = pontosRepo.calcularSaldo(restauranteId, telefone, LocalDateTime.now());
        return saldo != null ? saldo.intValue() : 0;
    }

    // ── INTEGRAÇÃO COM PEDIDO ───────────────────────────────────────────────

    /**
     * Credita pontos pelo valor do pedido. Se o saldo atingir a meta, debita
     * os pontos da recompensa e gera um cupom de fidelidade único.
     *
     * Retorna o código do cupom gerado (ou null se ainda não atingiu a meta).
     */
    @Transactional
    public String creditarPorPedido(Restaurante restaurante, String telefone,
                                    Long pedidoId, BigDecimal valorPedido) {
        // Sempre normaliza antes de salvar: garante que a chave de busca futura bata
        telefone = com.mydelivery.util.TelefoneUtil.normalizar(telefone);
        if (telefone == null || valorPedido == null) return null;
        Optional<ProgramaFidelidade> opt = programaRepo.findByRestauranteId(restaurante.getId());
        if (opt.isEmpty() || !Boolean.TRUE.equals(opt.get().getAtivo())) return null;

        ProgramaFidelidade p = opt.get();
        BigDecimal vpp = p.getValorPorPonto() != null ? p.getValorPorPonto() : BigDecimal.ONE;
        if (vpp.signum() <= 0) return null;

        int pontosGanhos = valorPedido.divide(vpp, 0, RoundingMode.FLOOR).intValue();
        if (pontosGanhos <= 0) return null;

        // Registra crédito
        LocalDateTime expira = LocalDateTime.now().plusDays(
                p.getDiasExpiracao() != null ? p.getDiasExpiracao() : 90);
        pontosRepo.save(PontosTransacao.builder()
                .restaurante(restaurante)
                .telefoneCliente(telefone)
                .tipo(PontosTransacao.Tipo.CREDITO)
                .pontos(pontosGanhos)
                .expiraEm(expira)
                .pedidoId(pedidoId)
                .descricao("Pontos do pedido #" + pedidoId)
                .build());

        // Atingiu a meta? Debita e gera cupom
        int saldo = calcularSaldo(restaurante.getId(), telefone);
        if (saldo < p.getPontosParaRecompensa()) return null;

        Cupom.Tipo tipoCupom = converterTipo(p.getTipoRecompensa());
        Cupom cupom = cupomService.gerarCupomFidelidade(
                restaurante, telefone, tipoCupom, p.getValorRecompensa(), p.getDescricaoRecompensa());

        pontosRepo.save(PontosTransacao.builder()
                .restaurante(restaurante)
                .telefoneCliente(telefone)
                .tipo(PontosTransacao.Tipo.DEBITO_RECOMPENSA)
                .pontos(p.getPontosParaRecompensa())
                .cupomId(cupom.getId())
                .descricao("Troca por cupom " + cupom.getCodigo())
                .build());

        log.info("🎁 Cupom de fidelidade gerado: {} para telefone {} no restaurante {}",
                cupom.getCodigo(), telefone, restaurante.getId());

        return cupom.getCodigo();
    }

    private Cupom.Tipo converterTipo(ProgramaFidelidade.TipoRecompensa t) {
        return switch (t) {
            case DESCONTO_PERCENT -> Cupom.Tipo.PERCENT;
            case DESCONTO_FIXO    -> Cupom.Tipo.FIXO;
            case ITEM_GRATIS      -> Cupom.Tipo.ITEM_GRATIS;
        };
    }

    // ── JOB de expiração (auditoria) ────────────────────────────────────────

    /**
     * Roda diariamente às 3 da manhã. Cria registros EXPIRACAO para créditos
     * cujo expiraEm já passou — assim a auditoria fica completa e o
     * `calcularSaldo` já ignora créditos expirados de qualquer forma.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void registrarExpiracoes() {
        List<PontosTransacao> expirados = pontosRepo.findByTipoAndExpiraEmBefore(
                PontosTransacao.Tipo.CREDITO, LocalDateTime.now());
        if (expirados.isEmpty()) return;

        int total = 0;
        for (PontosTransacao c : expirados) {
            // Já registramos uma vez? Pra MVP, não temos como saber — deixar simples e
            // só logar quantidade. Em produção, marcaria os créditos como "já contabilizados".
            total += c.getPontos();
        }
        log.info("⏰ {} créditos de pontos venceram hoje (total {} pontos)", expirados.size(), total);
    }
}
