package com.mydelivery.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.Mesa;
import com.mydelivery.model.MesaSessao;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.Restaurante;
import com.mydelivery.model.UsuarioGarcom;
import com.mydelivery.repository.MesaRepository;
import com.mydelivery.repository.MesaSessaoRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.repository.UsuarioGarcomRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orquestra o fluxo do garçom: login, mapa de mesas, abrir/fechar sessão.
 *
 * Princípios:
 *  - Apenas UMA sessão aberta por mesa simultaneamente — garantido por código.
 *  - Toda ação (abrir, lançar pedido, fechar) atualiza ultimaInteracaoEm,
 *    base do alerta de "mesa esquecida" (Fase 3).
 *  - Não toca em pedido aqui — quem cria pedido é PedidoService. Service só
 *    cuida da CONTAÍNER (mesa-sessão), não do conteúdo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GarcomService {

    private final RestauranteRepository restauranteRepo;
    private final MesaRepository mesaRepo;
    private final MesaSessaoRepository sessaoRepo;
    private final UsuarioGarcomRepository garcomRepo;
    private final PedidoRepository pedidoRepo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    // ─── LOGIN POR PIN ────────────────────────────────────────────────────

    /**
     * Tenta autenticar um garçom pelo PIN num dado restaurante.
     * Lista pequena (poucos garçons por restaurante), busca linear é OK e
     * resistente a timing attack (BCrypt.matches tem custo constante).
     */
    @Transactional(readOnly = true)
    public Optional<UsuarioGarcom> autenticar(Long restauranteId, String pin) {
        if (pin == null || pin.isBlank()) return Optional.empty();
        var ativos = garcomRepo.findByRestauranteIdAndAtivoTrueOrderByNomeAsc(restauranteId);
        for (var g : ativos) {
            if (encoder.matches(pin, g.getPinHash())) {
                return Optional.of(g);
            }
        }
        return Optional.empty();
    }

    // ─── CADASTRO (admin do restaurante) ──────────────────────────────────

    @Transactional
    public UsuarioGarcom criarGarcom(Long restauranteId, String nome, String pin) {
        if (pin == null || pin.length() < 4 || pin.length() > 8 || !pin.matches("\\d+")) {
            throw new IllegalArgumentException("PIN deve ter 4 a 8 dígitos numéricos");
        }
        UsuarioGarcom g = UsuarioGarcom.builder()
                .restauranteId(restauranteId)
                .nome(nome == null ? "Garçom" : nome.trim())
                .pinHash(encoder.encode(pin))
                .ativo(true)
                .build();
        return garcomRepo.save(g);
    }

    @Transactional
    public void desativarGarcom(Long restauranteId, Long garcomId) {
        garcomRepo.findById(garcomId).ifPresent(g -> {
            if (!g.getRestauranteId().equals(restauranteId)) {
                throw new SecurityException("Garçom não pertence a esse restaurante");
            }
            g.setAtivo(false);
            garcomRepo.save(g);
        });
    }

    // ─── MAPA DO SALÃO ────────────────────────────────────────────────────

    /**
     * Devolve TODAS as mesas do restaurante com info de sessão atual (se aberta).
     * Base do mapa visual + lista do garçom.
     *
     * Status efetivo da mesa é calculado aqui (não armazenado):
     *  - sem sessão aberta → LIVRE
     *  - sessão aberta → usa o status da sessão
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> mapaSalao(Long restauranteId) {
        var mesas = mesaRepo.findByRestauranteIdOrderByNomeAsc(restauranteId);
        var sessoesAbertas = sessaoRepo.findByRestauranteIdAndFechamentoEmIsNullOrderByAberturaEmAsc(restauranteId);
        // index por mesaId pra lookup O(1)
        Map<Long, MesaSessao> sessaoPorMesa = new java.util.HashMap<>();
        for (var s : sessoesAbertas) sessaoPorMesa.put(s.getMesa().getId(), s);

        List<Map<String, Object>> out = new java.util.ArrayList<>(mesas.size());
        var agora = LocalDateTime.now();
        for (var m : mesas) {
            if (!Boolean.TRUE.equals(m.getAtiva())) continue;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("mesaId", m.getId());
            info.put("nome", m.getNome());
            info.put("slug", m.getSlug());
            info.put("setor", m.getSetor());
            info.put("capacidade", m.getCapacidade());
            info.put("posicaoX", m.getPosicaoX());
            info.put("posicaoY", m.getPosicaoY());

            var sessao = sessaoPorMesa.get(m.getId());
            if (sessao == null) {
                info.put("status", "LIVRE");
                info.put("sessaoId", null);
                info.put("nomeCliente", null);
                info.put("pessoas", null);
                info.put("total", null);
                info.put("minutosOcupada", null);
                info.put("minutosSemInteracao", null);
            } else {
                info.put("status", sessao.getStatus().name());
                info.put("sessaoId", sessao.getId());
                info.put("nomeCliente", sessao.getNomeCliente());
                info.put("pessoas", sessao.getPessoas());
                info.put("total", sessao.getTotalAcumulado());
                info.put("minutosOcupada",
                        (int) Duration.between(sessao.getAberturaEm(), agora).toMinutes());
                info.put("minutosSemInteracao",
                        (int) Duration.between(sessao.getUltimaInteracaoEm(), agora).toMinutes());
            }
            out.add(info);
        }
        return out;
    }

    // ─── ABRIR / FECHAR SESSÃO ────────────────────────────────────────────

    @Transactional
    public MesaSessao abrirSessao(Long restauranteId, String slugMesa, Long garcomId,
                                   String nomeCliente, Integer pessoas, String telefoneCliente) {
        Mesa mesa = mesaRepo.findByRestauranteIdAndSlug(restauranteId, slugMesa)
                .orElseThrow(() -> new RuntimeException("Mesa não encontrada"));

        // Idempotência: se já tem sessão aberta, devolve a existente
        var jaAberta = sessaoRepo.findFirstByMesaIdAndFechamentoEmIsNullOrderByAberturaEmDesc(mesa.getId());
        if (jaAberta.isPresent()) {
            var s = jaAberta.get();
            // Permite atualizar nome/pessoas se ainda não tem
            boolean alterou = false;
            if (s.getNomeCliente() == null && nomeCliente != null) {
                s.setNomeCliente(nomeCliente); alterou = true;
            }
            if (telefoneCliente != null && (s.getTelefoneCliente() == null || s.getTelefoneCliente().isBlank())) {
                s.setTelefoneCliente(telefoneCliente.replaceAll("\\D", "")); alterou = true;
            }
            if (pessoas != null && pessoas > 0 && (s.getPessoas() == null || s.getPessoas() == 1)) {
                s.setPessoas(pessoas); alterou = true;
            }
            if (alterou) {
                s.setUltimaInteracaoEm(LocalDateTime.now());
                sessaoRepo.save(s);
            }
            return s;
        }

        var sessao = MesaSessao.builder()
                .mesa(mesa)
                .restauranteId(restauranteId)
                .garcomId(garcomId)
                .nomeCliente(nomeCliente != null ? nomeCliente.trim() : null)
                .telefoneCliente(telefoneCliente != null ? telefoneCliente.replaceAll("\\D", "") : null)
                .pessoas(pessoas != null && pessoas > 0 ? pessoas : 1)
                .status(MesaSessao.Status.ABERTA)
                .ultimaInteracaoEm(LocalDateTime.now())
                .totalAcumulado(BigDecimal.ZERO)
                .build();
        log.info("[Garçom] Abrindo sessão mesa={} cliente={} pessoas={}",
                mesa.getNome(), nomeCliente, pessoas);
        return sessaoRepo.save(sessao);
    }

    @Transactional
    public void marcarInteracao(Long sessaoId) {
        sessaoRepo.findById(sessaoId).ifPresent(s -> {
            s.setUltimaInteracaoEm(LocalDateTime.now());
            sessaoRepo.save(s);
        });
    }

    @Transactional
    public void atualizarStatus(Long sessaoId, MesaSessao.Status novo) {
        sessaoRepo.findById(sessaoId).ifPresent(s -> {
            s.setStatus(novo);
            s.setUltimaInteracaoEm(LocalDateTime.now());
            sessaoRepo.save(s);
        });
    }

    /**
     * Ajusta o totalAcumulado da sessão pelo delta. Usado quando pedido é
     * editado (delta = novo total - antigo) ou cancelado (delta = -total).
     * Best-effort — se sessão foi fechada nesse meio tempo, ignora.
     */
    @Transactional
    public void ajustarTotalSessao(Long sessaoId, java.math.BigDecimal delta) {
        if (sessaoId == null || delta == null || delta.signum() == 0) return;
        sessaoRepo.findById(sessaoId).ifPresent(s -> {
            java.math.BigDecimal base = s.getTotalAcumulado() == null ? java.math.BigDecimal.ZERO : s.getTotalAcumulado();
            java.math.BigDecimal novo = base.add(delta);
            if (novo.signum() < 0) novo = java.math.BigDecimal.ZERO; // proteção — nunca negativo
            s.setTotalAcumulado(novo);
            s.setUltimaInteracaoEm(LocalDateTime.now());
            sessaoRepo.save(s);
        });
    }

    @Transactional
    public MesaSessao fecharSessao(Long sessaoId, Long garcomId) {
        return fecharSessao(sessaoId, garcomId, null);
    }

    /**
     * Fecha a sessão registrando opcionalmente o payload de pagamentos
     * (divisão por pessoa + forma de pagamento).
     *
     * Também marca TODOS os pedidos não-cancelados da sessão como:
     *   - pago = true
     *   - status = ENTREGUE
     *
     * Sem isso, os pedidos ficavam abertos eternamente nos relatórios
     * mesmo depois do garçom fechar — bug real.
     *
     * Operação é idempotente: chamar 2x na mesma sessão fechada não causa
     * nada (apenas retorna a sessão como está).
     *
     * @param payload mapa com chaves opcionais "valorTotal", "comServico",
     *                "divisao": [{pessoa,total,formaPagamento}]. Pode ser null
     *                (mantém retrocompatibilidade com fluxo antigo).
     */
    @Transactional
    public MesaSessao fecharSessao(Long sessaoId, Long garcomId, Map<String, Object> payload) {
        var s = sessaoRepo.findById(sessaoId)
                .orElseThrow(() -> new RuntimeException("Sessão não encontrada"));
        if (s.getFechamentoEm() != null) return s; // idempotente

        // Marca todos os pedidos da sessão como pagos + entregues.
        // Sem isso, o restaurante vê os pedidos "abertos" mesmo após fechar.
        try {
            var pedidos = pedidoRepo.findBySessaoIdOrderByCriadoEmAsc(sessaoId);
            for (var p : pedidos) {
                if (p.getStatus() == Pedido.Status.CANCELADO) continue;
                p.setPago(true);
                if (p.getStatus() != Pedido.Status.ENTREGUE) {
                    p.setStatus(Pedido.Status.ENTREGUE);
                }
            }
            if (!pedidos.isEmpty()) pedidoRepo.saveAll(pedidos);
        } catch (Exception e) {
            // Não deixa falha de marcação derrubar o fechamento — loga e segue.
            log.warn("[Garçom] Falha ao marcar pedidos pagos na sessão {}: {}", sessaoId, e.getMessage());
        }

        // Persiste payload de pagamentos (se vier)
        if (payload != null && !payload.isEmpty()) {
            try {
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
                s.setPagamentosJson(json);
            } catch (Exception e) {
                log.warn("[Garçom] Falha ao serializar pagamentos: {}", e.getMessage());
            }
            Object v = payload.get("valorTotal");
            if (v != null) {
                try { s.setValorCobrado(new BigDecimal(v.toString())); }
                catch (Exception ignore) { /* valor mal formado, ignora */ }
            }
        }

        s.setStatus(MesaSessao.Status.FECHADA);
        s.setFechamentoEm(LocalDateTime.now());
        s.setUltimaInteracaoEm(LocalDateTime.now());
        log.info("[Garçom] Fechando sessão {} (garçom={}, divisao={})",
                sessaoId, garcomId, payload != null && payload.get("divisao") != null);
        return sessaoRepo.save(s);
    }

    @Transactional(readOnly = true)
    public Optional<MesaSessao> sessaoDaMesa(Long restauranteId, String slugMesa) {
        var mesa = mesaRepo.findByRestauranteIdAndSlug(restauranteId, slugMesa).orElse(null);
        if (mesa == null) return Optional.empty();
        return sessaoRepo.findFirstByMesaIdAndFechamentoEmIsNullOrderByAberturaEmDesc(mesa.getId());
    }
}
