package com.mydelivery.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.dto.restaurante.ConfiguracaoRequest;
import com.mydelivery.model.BairroEntrega;
import com.mydelivery.model.ConfiguracaoRestaurante;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConfiguracaoService {

    private final RestauranteRepository restauranteRepository;
    private final ConfiguracaoRestauranteRepository configRepo;

    @Transactional
    public Restaurante salvar(Restaurante restaurante, ConfiguracaoRequest req) {
        if (req.getNome() != null)         restaurante.setNome(req.getNome());
        if (req.getSlug() != null)         restaurante.setSlug(req.getSlug());
        if (req.getDescricao() != null)    restaurante.setDescricao(req.getDescricao());
        if (req.getTelefone() != null)     restaurante.setTelefone(req.getTelefone());
        if (req.getEndereco() != null)     restaurante.setEndereco(req.getEndereco());
        if (req.getRua() != null)          restaurante.setRua(req.getRua());
        if (req.getNumero() != null)       restaurante.setNumero(req.getNumero());
        if (req.getBairro() != null)       restaurante.setBairro(req.getBairro());
        if (req.getCep() != null)          restaurante.setCep(req.getCep());
        if (req.getCidade() != null)       restaurante.setCidade(req.getCidade());
        if (req.getEstado() != null)       restaurante.setEstado(req.getEstado());
        // CPF/CNPJ — aceita só dígitos pra evitar inconsistência de máscara entre cadastros.
        // String vazia = limpar o campo (ex: trocou de CPF pra CNPJ).
        if (req.getCnpj() != null) {
            String s = req.getCnpj().replaceAll("\\D", "");
            restaurante.setCnpj(s.isEmpty() ? null : s);
        }
        if (req.getCpf() != null) {
            String s = req.getCpf().replaceAll("\\D", "");
            restaurante.setCpf(s.isEmpty() ? null : s);
        }
        if (req.getCorPrimaria() != null)  restaurante.setCorPrimaria(req.getCorPrimaria());
        if (req.getTemaCardapio() != null) restaurante.setTemaCardapio(req.getTemaCardapio());
        if (req.getAberto() != null)       restaurante.setAberto(req.getAberto());
        if (req.getTempoEntrega() != null) restaurante.setTempoEntrega(req.getTempoEntrega());
        if (req.getTempoEntregaMax() != null) restaurante.setTempoEntregaMax(req.getTempoEntregaMax());
        if (req.getTaxaEntrega() != null)  restaurante.setTaxaEntrega(req.getTaxaEntrega());
        if (req.getPedidoMinimo() != null) restaurante.setPedidoMinimo(req.getPedidoMinimo());
        if (req.getQtdMesas() != null)     restaurante.setQtdMesas(req.getQtdMesas());
        if (req.getModos() != null)        restaurante.setModos(req.getModos());
        if (req.getPagamentos() != null)   restaurante.setPagamentos(req.getPagamentos());
        // PIX antecipado — só persiste a chave se a flag estiver ativa, evita lixo
        if (req.getExigirPixAntecipado() != null) restaurante.setExigirPixAntecipado(req.getExigirPixAntecipado());
        if (req.getChavePixAntecipado() != null) {
            String ch = req.getChavePixAntecipado().trim();
            restaurante.setChavePixAntecipado(ch.isEmpty() ? null : ch);
        }
        if (req.getTipoChavePixAntecipado() != null) {
            String tp = req.getTipoChavePixAntecipado().trim().toUpperCase();
            restaurante.setTipoChavePixAntecipado(tp.isEmpty() ? null : tp);
        }
        if (req.getBairrosAtendidos() != null) {
            // Filtra entradas inválidas (sem nome) e mapeia DTO → entidade
            restaurante.setBairrosAtendidos(req.getBairrosAtendidos().stream()
                    .filter(b -> b != null && b.getNome() != null && !b.getNome().isBlank())
                    .map(b -> new BairroEntrega(b.getNome().trim(), b.getTaxa()))
                    .toList());
        }

        // Botão "Confirmar via WhatsApp" no checkout do cliente — opt-in do dono.
        if (req.getConfirmacaoWhatsappAtiva() != null)
            restaurante.setConfirmacaoWhatsappAtiva(req.getConfirmacaoWhatsappAtiva());

        // Integração com balança de pesagem no Balcão (Web Serial API) — opt-in.
        if (req.getBalancaAtiva() != null)
            restaurante.setBalancaAtiva(req.getBalancaAtiva());

        // Automação de horário — toggles e cutoff
        if (req.getAberturaAutomatica() != null)
            restaurante.setAberturaAutomatica(req.getAberturaAutomatica());
        if (req.getPararPedidosAntesFechamento() != null)
            restaurante.setPararPedidosAntesFechamento(req.getPararPedidosAntesFechamento());
        if (req.getMinutosAntesFechamento() != null) {
            int v = req.getMinutosAntesFechamento();
            // Clamp pra evitar valores absurdos (0 a 120 min)
            if (v < 0) v = 0;
            if (v > 120) v = 120;
            restaurante.setMinutosAntesFechamento(v);
        }

        // Horários: aceita objeto (Map<dia, {aberto, abertura, fechamento}>) ou
        // string JSON. Serializa pra string TEXT no banco.
        if (req.getHorarios() != null) {
            try {
                String json = req.getHorarios() instanceof String
                        ? (String) req.getHorarios()
                        : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(req.getHorarios());
                restaurante.setHorariosJson(json);
            } catch (Exception ignore) { /* preserva valor antigo se serializar quebrar */ }
        }

        if (req.getAgendamento() != null) {
            var ag = req.getAgendamento();
            if (ag.getAtivo() != null)        restaurante.setAgendamentoAtivo(ag.getAtivo());
            if (ag.getAntecedencia() != null) restaurante.setAgendamentoAntecedencia(ag.getAntecedencia());
            if (ag.getIntervalo() != null)    restaurante.setAgendamentoIntervalo(ag.getIntervalo());
            if (ag.getSlots() != null)        restaurante.setAgendamentoSlots(ag.getSlots());
        }

        // ── Credenciais Mercado Pago (vivem em ConfiguracaoRestaurante, 1:1 com Restaurante) ──
        // Salvas só quando o request envia o campo, pra permitir edição parcial.
        if (req.getMpAccessToken() != null || req.getMpPublicKey() != null || req.getMpWebhookSecret() != null) {
            ConfiguracaoRestaurante cfg = configRepo.findByRestauranteId(restaurante.getId())
                    .orElseGet(() -> ConfiguracaoRestaurante.builder().restaurante(restaurante).build());
            if (req.getMpAccessToken() != null)   cfg.setMpAccessToken(blankToNull(req.getMpAccessToken()));
            if (req.getMpPublicKey() != null)     cfg.setMpPublicKey(blankToNull(req.getMpPublicKey()));
            if (req.getMpWebhookSecret() != null) cfg.setMpWebhookSecret(blankToNull(req.getMpWebhookSecret()));
            configRepo.save(cfg);
        }

        return restauranteRepository.save(restaurante);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}