package com.mydelivery.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.cupom.CupomDTO;
import com.mydelivery.dto.cupom.ValidarCupomRequest;
import com.mydelivery.dto.cupom.ValidarCupomResponse;
import com.mydelivery.model.Cupom;
import com.mydelivery.model.CupomUso;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.CupomRepository;
import com.mydelivery.repository.CupomUsoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CupomService {

    private static final String CHARS_CODIGO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // sem 0/O/I/1
    private static final SecureRandom RNG = new SecureRandom();

    private final CupomRepository cupomRepository;
    private final CupomUsoRepository cupomUsoRepository;
    private final RestauranteRepository restauranteRepository;

    // ── ADMIN: CRUD ──────────────────────────────────────────────────────────

    public List<CupomDTO> listarPorRestaurante(Long restauranteId) {
        return cupomRepository.findByRestauranteIdOrderByCriadoEmDesc(restauranteId)
                .stream().map(CupomDTO::fromEntity).collect(Collectors.toList());
    }

    @Transactional
    public CupomDTO criar(Long restauranteId, CupomDTO dto) {
        Restaurante r = restauranteRepository.findById(restauranteId)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));

        String codigo = dto.getCodigo() != null && !dto.getCodigo().isBlank()
                ? dto.getCodigo().trim().toUpperCase()
                : gerarCodigoUnico(restauranteId, "CUP");

        // Garante que não exista
        cupomRepository.findByCodigoIgnoreCaseAndRestauranteId(codigo, restauranteId)
                .ifPresent(c -> { throw new RuntimeException("Já existe cupom com esse código"); });

        Cupom c = Cupom.builder()
                .restaurante(r)
                .codigo(codigo)
                .tipo(Cupom.Tipo.valueOf(dto.getTipo()))
                .valor(dto.getValor())
                .descricao(dto.getDescricao())
                .pedidoMinimo(dto.getPedidoMinimo())
                .validadeInicio(dto.getValidadeInicio())
                .validadeFim(dto.getValidadeFim())
                .limiteTotal(dto.getLimiteTotal())
                .limitePorCliente(dto.getLimitePorCliente())
                .modosAplicaveis(dto.getModosAplicaveis() != null ? dto.getModosAplicaveis() : List.of())
                .ativo(dto.getAtivo() != null ? dto.getAtivo() : true)
                .origem(Cupom.Origem.MANUAL)
                .build();

        return CupomDTO.fromEntity(cupomRepository.save(c));
    }

    @Transactional
    public CupomDTO atualizar(Long restauranteId, Long id, CupomDTO dto) {
        Cupom c = cupomRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Cupom não encontrado"));
        if (!c.getRestaurante().getId().equals(restauranteId)) {
            throw new RuntimeException("Acesso negado");
        }
        if (c.getOrigem() == Cupom.Origem.FIDELIDADE) {
            throw new RuntimeException("Cupons de fidelidade não podem ser editados");
        }
        if (dto.getCodigo() != null && !dto.getCodigo().isBlank()) c.setCodigo(dto.getCodigo().trim().toUpperCase());
        if (dto.getTipo() != null)            c.setTipo(Cupom.Tipo.valueOf(dto.getTipo()));
        if (dto.getValor() != null)           c.setValor(dto.getValor());
        if (dto.getDescricao() != null)       c.setDescricao(dto.getDescricao());
        c.setPedidoMinimo(dto.getPedidoMinimo());
        c.setValidadeInicio(dto.getValidadeInicio());
        c.setValidadeFim(dto.getValidadeFim());
        c.setLimiteTotal(dto.getLimiteTotal());
        c.setLimitePorCliente(dto.getLimitePorCliente());
        if (dto.getModosAplicaveis() != null) c.setModosAplicaveis(dto.getModosAplicaveis());
        if (dto.getAtivo() != null)           c.setAtivo(dto.getAtivo());
        return CupomDTO.fromEntity(cupomRepository.save(c));
    }

    @Transactional
    public void deletar(Long restauranteId, Long id) {
        Cupom c = cupomRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Cupom não encontrado"));
        if (!c.getRestaurante().getId().equals(restauranteId)) {
            throw new RuntimeException("Acesso negado");
        }
        cupomRepository.delete(c);
    }

    // ── PÚBLICO: banner no cardápio ─────────────────────────────────────────

    public List<CupomDTO> listarPublicos(String slug) {
        return cupomRepository.listarCuponsPublicos(slug, LocalDateTime.now())
                .stream().map(CupomDTO::fromEntity).collect(Collectors.toList());
    }

    // ── VALIDAÇÃO no checkout ───────────────────────────────────────────────

    public ValidarCupomResponse validar(ValidarCupomRequest req) {
        if (req.getCodigo() == null || req.getCodigo().isBlank()) {
            return ValidarCupomResponse.builder().valido(false).mensagem("Informe um código").build();
        }
        // Normaliza o telefone pra bater com o salvo nos limites/cupom de fidelidade
        req.setTelefone(com.mydelivery.util.TelefoneUtil.normalizar(req.getTelefone()));
        var opt = cupomRepository.findByCodigoIgnoreCaseAndRestauranteSlug(
                req.getCodigo().trim().toUpperCase(), req.getSlug());
        if (opt.isEmpty()) {
            return ValidarCupomResponse.builder().valido(false).mensagem("Cupom não encontrado").build();
        }
        Cupom c = opt.get();
        LocalDateTime agora = LocalDateTime.now();

        if (!Boolean.TRUE.equals(c.getAtivo()))
            return invalid(c, "Cupom inativo");

        if (c.getValidadeInicio() != null && agora.isBefore(c.getValidadeInicio()))
            return invalid(c, "Cupom ainda não está válido");

        if (c.getValidadeFim() != null && agora.isAfter(c.getValidadeFim()))
            return invalid(c, "Cupom expirado");

        // Cupom de fidelidade: vinculado a um telefone específico, uso único
        if (c.getOrigem() == Cupom.Origem.FIDELIDADE) {
            if (c.getUsadoEm() != null)
                return invalid(c, "Cupom já foi utilizado");
            if (req.getTelefone() == null || !req.getTelefone().equals(c.getTelefoneCliente()))
                return invalid(c, "Cupom não pertence a este número");
        }

        if (c.getPedidoMinimo() != null && req.getSubtotal() != null
                && req.getSubtotal().compareTo(c.getPedidoMinimo()) < 0)
            return invalid(c, "Pedido mínimo de R$ " + c.getPedidoMinimo() + " não atingido");

        if (c.getModosAplicaveis() != null && !c.getModosAplicaveis().isEmpty()
                && req.getModo() != null
                && !c.getModosAplicaveis().contains(req.getModo().toLowerCase()))
            return invalid(c, "Cupom não válido para esse tipo de pedido");

        if (c.getLimiteTotal() != null) {
            long usos = cupomUsoRepository.countByCupomId(c.getId());
            if (usos >= c.getLimiteTotal())
                return invalid(c, "Cupom esgotado");
        }

        if (c.getLimitePorCliente() != null && req.getTelefone() != null) {
            long usosCliente = cupomUsoRepository.countByCupomIdAndTelefoneCliente(c.getId(), req.getTelefone());
            if (usosCliente >= c.getLimitePorCliente())
                return invalid(c, "Você já usou esse cupom o máximo de vezes");
        }

        // Calcula desconto efetivo
        BigDecimal desconto = calcularDesconto(c, req.getSubtotal() != null ? req.getSubtotal() : BigDecimal.ZERO);

        return ValidarCupomResponse.builder()
                .valido(true)
                .codigo(c.getCodigo())
                .tipo(c.getTipo().name())
                .desconto(desconto)
                .descricao(c.getDescricao())
                .mensagem("Cupom aplicado!")
                .build();
    }

    /**
     * Calcula o desconto efetivo do cupom dado o subtotal do pedido.
     * Para ITEM_GRATIS retorna ZERO (o "desconto" é o brinde, não monetário).
     */
    public BigDecimal calcularDesconto(Cupom c, BigDecimal subtotal) {
        if (c.getTipo() == Cupom.Tipo.PERCENT && c.getValor() != null && subtotal != null) {
            BigDecimal d = subtotal.multiply(c.getValor()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            // Não pode descontar mais que o subtotal
            return d.min(subtotal);
        }
        if (c.getTipo() == Cupom.Tipo.FIXO && c.getValor() != null && subtotal != null) {
            return c.getValor().min(subtotal);
        }
        return BigDecimal.ZERO;
    }

    // ── Aplicação no momento do pedido (chamado pelo PedidoService) ─────────

    @Transactional
    public CupomUso registrarUso(Cupom cupom, Long pedidoId, String telefone, BigDecimal descontoAplicado) {
        CupomUso uso = CupomUso.builder()
                .cupom(cupom)
                .pedidoId(pedidoId)
                .telefoneCliente(telefone)
                .descontoAplicado(descontoAplicado)
                .build();
        uso = cupomUsoRepository.save(uso);
        // Cupons de fidelidade são one-shot
        if (cupom.getOrigem() == Cupom.Origem.FIDELIDADE) {
            cupom.setUsadoEm(LocalDateTime.now());
            cupom.setAtivo(false);
            cupomRepository.save(cupom);
        }
        return uso;
    }

    // ── Geração de cupom de fidelidade ──────────────────────────────────────

    /**
     * Cria um cupom único vinculado ao telefone, com prefixo "FID-".
     * Validade: 90 dias a partir da criação.
     */
    @Transactional
    public Cupom gerarCupomFidelidade(Restaurante r, String telefone,
                                      Cupom.Tipo tipo, BigDecimal valor, String descricao) {
        // Normaliza o telefone pra garantir consistência no banco
        String telNorm = com.mydelivery.util.TelefoneUtil.normalizar(telefone);
        String codigo = gerarCodigoUnico(r.getId(), "FID");
        Cupom c = Cupom.builder()
                .restaurante(r)
                .codigo(codigo)
                .tipo(tipo)
                .valor(valor)
                .descricao(descricao)
                .validadeFim(LocalDateTime.now().plusDays(90))
                .limitePorCliente(1)
                .ativo(true)
                .origem(Cupom.Origem.FIDELIDADE)
                .telefoneCliente(telNorm)
                .build();
        return cupomRepository.save(c);
    }

    private String gerarCodigoUnico(Long restauranteId, String prefixo) {
        for (int tentativa = 0; tentativa < 20; tentativa++) {
            String codigo = prefixo + "-" + codigoAleatorio(6);
            if (cupomRepository.findByCodigoIgnoreCaseAndRestauranteId(codigo, restauranteId).isEmpty()) {
                return codigo;
            }
        }
        throw new RuntimeException("Falha ao gerar código único de cupom");
    }

    private String codigoAleatorio(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(CHARS_CODIGO.charAt(RNG.nextInt(CHARS_CODIGO.length())));
        return sb.toString();
    }

    private ValidarCupomResponse invalid(Cupom c, String msg) {
        return ValidarCupomResponse.builder()
                .valido(false)
                .codigo(c.getCodigo())
                .mensagem(msg)
                .build();
    }
}
