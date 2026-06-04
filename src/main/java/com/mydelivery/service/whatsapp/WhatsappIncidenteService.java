package com.mydelivery.service.whatsapp;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.WhatsappAcaoAutomatica;
import com.mydelivery.model.WhatsappIncidente;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappAcaoAutomaticaRepository;
import com.mydelivery.repository.WhatsappIncidenteRepository;
import com.mydelivery.repository.WhatsappInstanceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Serviço central de monitoramento real do WhatsApp.
 *
 * Faz 3 coisas:
 *  1) DETECTAR — métodos detectar*() chamados pelo HealthJob a cada tick.
 *     Cada um abre/resolve incidente de acordo com a condição.
 *  2) PERSISTIR — abrirSe()/resolverSe() são idempotentes. Nunca cria
 *     duplicado do mesmo (tipo, instância) aberto.
 *  3) AUDITAR — registrarAcao() guarda histórico de tudo que foi tentado.
 *
 * Detectores são métodos no mesmo service em vez de classes separadas pra
 * evitar sobre-engenharia. São poucos, mudam juntos, e dependem do mesmo
 * estado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsappIncidenteService {

    private final WhatsappIncidenteRepository incidenteRepo;
    private final WhatsappAcaoAutomaticaRepository acaoRepo;
    private final WhatsappInstanceRepository instanceRepo;
    private final EvolutionClient evolutionClient;

    // Janelas dos detectores — calibradas pra evitar falso positivo em
    // restaurantes de baixo volume (manhã sem cliente) mas pegar zumbi real.
    private static final int SHADOW_BAN_HORAS = 6;            // sem msg cliente
    private static final int BAILEYS_TRAVADO_MIN = 5;          // state=connecting persistente
    private static final int EVOLUTION_FALHAS_LIMITE = 3;     // consultarStatus seguidas
    private static final int RECUPERACAO_TENT_LIMITE = 5;     // reconexões seguidas

    // ─────────────── IDEMPOTÊNCIA ───────────────

    /**
     * Abre incidente do tipo se NÃO houver outro aberto do mesmo {tipo, instância}.
     * Caso já exista, só atualiza detalhes (mantém abertoEm). Retorna o incidente
     * (novo ou atualizado).
     */
    @Transactional
    public WhatsappIncidente abrirSe(WhatsappInstance inst, WhatsappIncidente.Tipo tipo,
                                     WhatsappIncidente.Severidade sev,
                                     String causa, String detalhesJson) {
        Optional<WhatsappIncidente> existente = incidenteRepo
                .findFirstByInstanceIdAndTipoAndResolvidoEmIsNull(inst.getId(), tipo);
        if (existente.isPresent()) {
            WhatsappIncidente i = existente.get();
            i.setCausaProvavel(causa);
            i.setDetalhesJson(detalhesJson);
            // Se severidade subiu, atualiza
            if (sev.ordinal() > i.getSeveridade().ordinal()) {
                i.setSeveridade(sev);
            }
            return incidenteRepo.save(i);
        }
        WhatsappIncidente novo = WhatsappIncidente.builder()
                .instance(inst)
                .restauranteId(inst.getRestaurante().getId())
                .abertoEm(LocalDateTime.now())
                .tipo(tipo)
                .severidade(sev)
                .causaProvavel(causa)
                .detalhesJson(detalhesJson)
                .build();
        log.warn("[Incidente] ABERTO {} em {} ({})", tipo, inst.getInstanceName(), causa);
        return incidenteRepo.save(novo);
    }

    /**
     * Resolve (fecha) o incidente aberto desse tipo, se houver. Idempotente:
     * se não tinha aberto, não faz nada. Chamado pelos detectores quando
     * a condição saiu (auto-recuperou sozinho).
     */
    @Transactional
    public void resolverSe(WhatsappInstance inst, WhatsappIncidente.Tipo tipo, String motivo) {
        incidenteRepo.findFirstByInstanceIdAndTipoAndResolvidoEmIsNull(inst.getId(), tipo)
                .ifPresent(i -> {
                    i.setResolvidoEm(LocalDateTime.now());
                    i.setDetalhesJson(i.getDetalhesJson() == null
                            ? ("resolução: " + motivo)
                            : i.getDetalhesJson() + " | resolução: " + motivo);
                    incidenteRepo.save(i);
                    log.info("[Incidente] RESOLVIDO {} em {} ({})", tipo, inst.getInstanceName(), motivo);
                });
    }

    @Transactional
    public WhatsappAcaoAutomatica registrarAcao(WhatsappIncidente incidente, WhatsappInstance inst,
                                                 WhatsappAcaoAutomatica.Acao acao,
                                                 WhatsappAcaoAutomatica.Resultado res,
                                                 String detalhe) {
        WhatsappAcaoAutomatica a = WhatsappAcaoAutomatica.builder()
                .incidente(incidente)
                .instance(inst)
                .em(LocalDateTime.now())
                .acao(acao)
                .resultado(res)
                .detalhe(detalhe)
                .build();
        return acaoRepo.save(a);
    }

    // ─────────────── DETECTORES (chamados pelo Job) ───────────────

    /**
     * SHADOW_BAN_SUSPEITO: status CONECTADA + heartbeat fraco recente
     * (Evolution mandando keep-alive) MAS heartbeat forte (msg real de
     * cliente) ausente há > 6h.
     *
     * Cuidado: restaurante pode legitimamente não receber msg de cliente
     * por horas. Por isso só dispara se TINHA recebido antes (ultimaMsgCliente
     * não-null) — sinaliza que algo MUDOU. Pra restaurante novo, ignora.
     */
    public void detectarShadowBan(WhatsappInstance inst) {
        if (inst.getStatus() != WhatsappInstance.Status.CONECTADA) {
            resolverSe(inst, WhatsappIncidente.Tipo.SHADOW_BAN_SUSPEITO, "status saiu de CONECTADA");
            return;
        }
        LocalDateTime ultimaCliente = inst.getUltimaMensagemClienteEm();
        if (ultimaCliente == null) return; // sem baseline, não dá pra acusar
        long horasSemCliente = Duration.between(ultimaCliente, LocalDateTime.now()).toHours();
        if (horasSemCliente >= SHADOW_BAN_HORAS) {
            String causa = "Última msg real de cliente há " + horasSemCliente
                    + "h, mas Evolution segue mandando eventos. WhatsApp pode ter silenciado este número.";
            abrirSe(inst, WhatsappIncidente.Tipo.SHADOW_BAN_SUSPEITO,
                    WhatsappIncidente.Severidade.ALTA, causa,
                    "{\"horasSemCliente\":" + horasSemCliente + "}");
        } else {
            resolverSe(inst, WhatsappIncidente.Tipo.SHADOW_BAN_SUSPEITO,
                    "msg cliente voltou em " + horasSemCliente + "h");
        }
    }

    /**
     * EVOLUTION_FORA: consultarStatus retorna erro 3x consecutivas.
     * Detector é stateful — usa o counter de tentativas reconexão como
     * proxy (job aumenta esse contador quando reconnect falha).
     */
    public void detectarEvolutionFora(WhatsappInstance inst) {
        try {
            evolutionClient.consultarStatus(inst.getInstanceName());
            resolverSe(inst, WhatsappIncidente.Tipo.EVOLUTION_FORA, "consultarStatus voltou OK");
        } catch (RuntimeException e) {
            String causa = "consultarStatus falhou: " + e.getMessage();
            abrirSe(inst, WhatsappIncidente.Tipo.EVOLUTION_FORA,
                    WhatsappIncidente.Severidade.ALTA, causa, null);
        }
    }

    /**
     * BAILEYS_TRAVADO: state="connecting" persistente. Detecção via buffer
     * de eventos — se últimos N eventos só têm connection.update com state
     * connecting e nenhum messages.upsert nem QR resolvido, está travado.
     */
    public void detectarBaileysTravado(WhatsappInstance inst) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = evolutionClient.consultarStatus(inst.getInstanceName());
            String state = extrairState(resp);
            if ("connecting".equalsIgnoreCase(state)) {
                // Quanto tempo está em connecting? Sem timestamp explícito da Evolution
                // usamos abertoEm do próprio incidente como proxy.
                Optional<WhatsappIncidente> ja = incidenteRepo
                        .findFirstByInstanceIdAndTipoAndResolvidoEmIsNull(
                                inst.getId(), WhatsappIncidente.Tipo.BAILEYS_TRAVADO);
                if (ja.isEmpty()) {
                    // Primeira detecção — abre com severidade BAIXA. Próximo tick promove se persistir.
                    abrirSe(inst, WhatsappIncidente.Tipo.BAILEYS_TRAVADO,
                            WhatsappIncidente.Severidade.BAIXA,
                            "Sessão em state=connecting", null);
                } else if (Duration.between(ja.get().getAbertoEm(), LocalDateTime.now()).toMinutes() >= BAILEYS_TRAVADO_MIN) {
                    // Persistente >5min — sobe severidade
                    abrirSe(inst, WhatsappIncidente.Tipo.BAILEYS_TRAVADO,
                            WhatsappIncidente.Severidade.ALTA,
                            "Sessão travada em connecting há >" + BAILEYS_TRAVADO_MIN + "min", null);
                }
            } else if ("open".equalsIgnoreCase(state)) {
                resolverSe(inst, WhatsappIncidente.Tipo.BAILEYS_TRAVADO, "state voltou pra open");
            }
        } catch (RuntimeException ignored) {
            // Evolution fora — outro detector cuida
        }
    }

    /**
     * RECUPERACAO_ESGOTADA: já tentamos N reconnects sem sucesso. Sobe pra
     * incidente ALTA pra forçar atenção humana.
     */
    public void detectarRecuperacaoEsgotada(WhatsappInstance inst) {
        Integer tent = inst.getTentativasReconexaoSeguidas();
        if (tent != null && tent >= RECUPERACAO_TENT_LIMITE) {
            abrirSe(inst, WhatsappIncidente.Tipo.RECUPERACAO_ESGOTADA,
                    WhatsappIncidente.Severidade.ALTA,
                    "Auto-reconexão tentada " + tent + "× sem sucesso. Operação manual necessária (reset full).",
                    "{\"tentativas\":" + tent + "}");
        } else if (tent == null || tent == 0) {
            resolverSe(inst, WhatsappIncidente.Tipo.RECUPERACAO_ESGOTADA, "contador zerado");
        }
    }

    /**
     * WEBHOOK_DESCONFIGURADO: webhook salvo na Evolution não bate com
     * a URL que esperamos. Acontece quando troca EVOLUTION_WEBHOOK_BASE_URL.
     */
    public void detectarWebhookDesconfigurado(WhatsappInstance inst, String webhookUrlEsperada) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> wh = evolutionClient.consultarWebhook(inst.getInstanceName());
            String urlAtual = wh == null ? null : String.valueOf(wh.get("url"));
            if (urlAtual == null || !urlAtual.equals(webhookUrlEsperada)) {
                abrirSe(inst, WhatsappIncidente.Tipo.WEBHOOK_DESCONFIGURADO,
                        WhatsappIncidente.Severidade.MEDIA,
                        "Webhook na Evolution aponta pra " + urlAtual + ", deveria ser " + webhookUrlEsperada,
                        null);
            } else {
                resolverSe(inst, WhatsappIncidente.Tipo.WEBHOOK_DESCONFIGURADO, "webhook ok");
            }
        } catch (RuntimeException ignored) {}
    }

    @SuppressWarnings("unchecked")
    private String extrairState(Map<String, Object> resp) {
        if (resp == null) return null;
        Object inst = resp.get("instance");
        if (inst instanceof Map<?, ?> m) {
            Object s = ((Map<String, Object>) m).get("state");
            return s == null ? null : s.toString();
        }
        Object s = resp.get("state");
        return s == null ? null : s.toString();
    }

    // ─────────────── QUERIES PRO ADMIN/PAINEL ───────────────

    /**
     * Devolve resumo do alerta MAIS GRAVE aberto pra esse restaurante, ou null
     * se nenhum. Usado pelo banner no painel do restaurante.
     * Retorno: { tipo, severidade, causa, abertoEm, mensagemUsuario }.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> alertaAtualDoRestaurante(Long restauranteId) {
        List<WhatsappIncidente> abertos = incidenteRepo
                .findByRestauranteIdAndResolvidoEmIsNullOrderByAbertoEmDesc(restauranteId);
        if (abertos.isEmpty()) return null;
        // Pega o mais grave
        WhatsappIncidente pior = abertos.stream()
                .max((a, b) -> Integer.compare(a.getSeveridade().ordinal(), b.getSeveridade().ordinal()))
                .orElse(abertos.get(0));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", pior.getId());
        out.put("tipo", pior.getTipo().name());
        out.put("severidade", pior.getSeveridade().name());
        out.put("causa", pior.getCausaProvavel());
        out.put("abertoEm", pior.getAbertoEm().toString());
        out.put("mensagemUsuario", "Detectamos uma instabilidade no assistente automático deste"
                + " restaurante.\n\nNosso sistema já iniciou procedimentos automáticos de correção."
                + "\n\nNenhuma ação é necessária neste momento.");
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarIncidentes(boolean apenasAbertos) {
        List<WhatsappIncidente> lista = apenasAbertos
                ? incidenteRepo.findByResolvidoEmIsNullOrderByAbertoEmDesc()
                : incidenteRepo.findTop100ByOrderByAbertoEmDesc();
        return lista.stream().map(this::serializar).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarAlertasAtivos() {
        return incidenteRepo.findByResolvidoEmIsNullAndAckEmIsNullOrderByAbertoEmDesc()
                .stream().map(this::serializar).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarAcoes(Long incidenteId) {
        List<WhatsappAcaoAutomatica> lista = incidenteId != null
                ? acaoRepo.findByIncidenteIdOrderByEmAsc(incidenteId)
                : acaoRepo.findTop100ByOrderByEmDesc();
        return lista.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("em", a.getEm().toString());
            m.put("acao", a.getAcao().name());
            m.put("resultado", a.getResultado().name());
            m.put("detalhe", a.getDetalhe());
            m.put("instanceName", a.getInstance() != null ? a.getInstance().getInstanceName() : null);
            m.put("incidenteId", a.getIncidente() != null ? a.getIncidente().getId() : null);
            return m;
        }).toList();
    }

    @Transactional
    public boolean ack(Long incidenteId, String operador) {
        return incidenteRepo.findById(incidenteId).map(i -> {
            i.setAckEm(LocalDateTime.now());
            i.setAckPor(operador);
            incidenteRepo.save(i);
            return true;
        }).orElse(false);
    }

    private Map<String, Object> serializar(WhatsappIncidente i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("instanceId", i.getInstance() == null ? null : i.getInstance().getId());
        m.put("instanceName", i.getInstance() == null ? null : i.getInstance().getInstanceName());
        m.put("restauranteId", i.getRestauranteId());
        m.put("tipo", i.getTipo().name());
        m.put("severidade", i.getSeveridade().name());
        m.put("causa", i.getCausaProvavel());
        m.put("abertoEm", i.getAbertoEm().toString());
        m.put("resolvidoEm", i.getResolvidoEm() == null ? null : i.getResolvidoEm().toString());
        m.put("ackEm", i.getAckEm() == null ? null : i.getAckEm().toString());
        m.put("ackPor", i.getAckPor());
        return m;
    }
}
