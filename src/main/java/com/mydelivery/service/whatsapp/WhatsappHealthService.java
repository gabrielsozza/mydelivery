package com.mydelivery.service.whatsapp;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.WhatsappHealthLog;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappHealthLogRepository;
import com.mydelivery.repository.WhatsappInstanceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Calcula o estado REAL de saúde do bot.
 *
 * Heurística revisada — distingue HEARTBEAT FRACO (Evolution → backend está
 * vivo, mas qualquer event-spam da Evolution conta) de HEARTBEAT FORTE
 * (mensagem REAL de cliente chegou):
 *
 *   🟢 OPERACIONAL — status=CONECTADA E
 *                    heartbeat fraco <= 15min (Evolution ainda fala com a gente)
 *                    E (heartbeat forte <= 6h OU nunca houve msg cliente ainda)
 *   🟡 INSTÁVEL    — status=CONECTADA mas:
 *                    heartbeat fraco > 15min (Evolution não nos manda nada)
 *                    OU heartbeat forte > 6h (clientes mandam msg e bot não vê)
 *   🔴 OFFLINE     — status != CONECTADA OU heartbeat fraco > 1h
 *
 * Antes: qualquer CONNECTION_UPDATE periódico da Evolution renovava o
 * heartbeat e o gráfico mostrava OPERACIONAL mesmo com bot dormindo. Agora
 * separamos: heartbeat fraco prova conectividade, heartbeat forte prova
 * que MESSAGES_UPSERT está chegando de verdade.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsappHealthService {

    private final WhatsappHealthLogRepository healthLogRepo;
    private final WhatsappInstanceRepository instanceRepo;
    // EvolutionClient é injetado por field pra evitar ciclo de injeção em construtor
    // quando WhatsappService também depende deste health service.
    @Autowired
    private EvolutionClient evolutionClient;

    // Janelas — heartbeat FRACO (qualquer evento, inclusive keep-alive Evolution)
    private static final int MIN_FRACO_OPERACIONAL = 15;   // até 15min = ok
    private static final int MIN_FRACO_OFFLINE     = 60;   // > 1h sem evento = offline
    // Janela — heartbeat FORTE (mensagem real de cliente)
    private static final int MIN_FORTE_INSTAVEL    = 360;  // > 6h sem msg cliente = suspeito

    /** Calcula estado atual sem persistir. */
    public WhatsappHealthLog.Estado avaliarEstado(WhatsappInstance inst) {
        if (inst.getStatus() != WhatsappInstance.Status.CONECTADA) {
            return WhatsappHealthLog.Estado.OFFLINE;
        }
        Integer minFraco = minutosSemEventoEvolution(inst);
        // Sem heartbeat fraco nenhum → instância recém-conectada ou sem dados;
        // não é OFFLINE definitivo, mas tampouco é OPERACIONAL.
        if (minFraco == null) return WhatsappHealthLog.Estado.INSTAVEL;
        if (minFraco > MIN_FRACO_OFFLINE) return WhatsappHealthLog.Estado.OFFLINE;
        if (minFraco > MIN_FRACO_OPERACIONAL) return WhatsappHealthLog.Estado.INSTAVEL;

        // Heartbeat fraco OK. Checa o forte (cliente realmente conseguiu mandar msg).
        Integer minForte = minutosSemMensagemCliente(inst);
        if (minForte != null && minForte > MIN_FORTE_INSTAVEL) {
            // Evolution responde mas faz 6h+ que ninguém manda msg pro bot —
            // pode ser loja parada OU pode ser shadow-ban silencioso.
            // Marcamos INSTAVEL pro operador ver, mas não OFFLINE (pode ser legítimo).
            return WhatsappHealthLog.Estado.INSTAVEL;
        }
        return WhatsappHealthLog.Estado.OPERACIONAL;
    }

    /** Minutos desde QUALQUER evento da Evolution (keep-alive ou msg). */
    public Integer minutosSemEventoEvolution(WhatsappInstance inst) {
        LocalDateTime ultima = inst.getUltimaMensagemRecebidaEm();
        if (ultima == null) return null;
        return (int) Duration.between(ultima, LocalDateTime.now()).toMinutes();
    }

    /** Minutos desde a última MENSAGEM REAL de cliente (MESSAGES_UPSERT). */
    public Integer minutosSemMensagemCliente(WhatsappInstance inst) {
        LocalDateTime ultima = inst.getUltimaMensagemClienteEm();
        if (ultima == null) return null;
        return (int) Duration.between(ultima, LocalDateTime.now()).toMinutes();
    }

    /** Alias retrocompatível — mantém callers antigos enquanto migramos. */
    public Integer minutosSemMensagem(WhatsappInstance inst) {
        return minutosSemEventoEvolution(inst);
    }

    /** Snapshot atual completo pro frontend (admin + dono do restaurante). */
    public Map<String, Object> resumoAtual(WhatsappInstance inst) {
        Map<String, Object> r = new LinkedHashMap<>();
        WhatsappHealthLog.Estado estado = avaliarEstado(inst);
        r.put("estado", estado.name());
        r.put("status", inst.getStatus().name());
        r.put("ultimaMensagemRecebidaEm",
                inst.getUltimaMensagemRecebidaEm() == null ? null : inst.getUltimaMensagemRecebidaEm().toString());
        r.put("ultimaMensagemClienteEm",
                inst.getUltimaMensagemClienteEm() == null ? null : inst.getUltimaMensagemClienteEm().toString());
        r.put("ultimaRespostaEnviadaEm",
                inst.getUltimaRespostaEnviadaEm() == null ? null : inst.getUltimaRespostaEnviadaEm().toString());
        r.put("minutosSemEvento", minutosSemEventoEvolution(inst));
        r.put("minutosSemMensagemCliente", minutosSemMensagemCliente(inst));
        // alias antigo pra não quebrar admin
        r.put("minutosSemMensagem", minutosSemEventoEvolution(inst));
        r.put("tentativasReconexao", inst.getTentativasReconexaoSeguidas());
        r.put("botAtivo", inst.getBotAtivo());
        return r;
    }

    /** Histórico das últimas N horas pra gráfico. */
    public List<WhatsappHealthLog> historico(Long instanceId, int horas) {
        LocalDateTime desde = LocalDateTime.now().minusHours(horas);
        return healthLogRepo.findByInstanceIdAndEmGreaterThanEqualOrderByEmAsc(instanceId, desde);
    }

    /** Marca tentativa de reconexão e chama Evolution.restart(). */
    @Transactional
    public boolean tentarReconectar(WhatsappInstance inst) {
        try {
            log.warn("[WAHealth] tentando reconexão de {} (tentativa {})",
                    inst.getInstanceName(), inst.getTentativasReconexaoSeguidas() + 1);
            evolutionClient.restart(inst.getInstanceName());
            inst.setUltimaTentativaReconexaoEm(LocalDateTime.now());
            inst.setTentativasReconexaoSeguidas(inst.getTentativasReconexaoSeguidas() + 1);
            instanceRepo.save(inst);
            return true;
        } catch (Exception e) {
            log.error("[WAHealth] falha ao reconectar {}: {}", inst.getInstanceName(), e.getMessage());
            inst.setUltimaTentativaReconexaoEm(LocalDateTime.now());
            inst.setTentativasReconexaoSeguidas(inst.getTentativasReconexaoSeguidas() + 1);
            instanceRepo.save(inst);
            return false;
        }
    }

    /** Persiste um snapshot. Chamado pelo job a cada 5min. */
    @Transactional
    public WhatsappHealthLog registrarSnapshot(WhatsappInstance inst, boolean reconexaoDisparada) {
        WhatsappHealthLog log = WhatsappHealthLog.builder()
                .instance(inst)
                .em(LocalDateTime.now())
                .estado(avaliarEstado(inst))
                .minutosSemMensagem(minutosSemMensagem(inst))
                .reconexaoDisparada(reconexaoDisparada)
                .build();
        return healthLogRepo.save(log);
    }
}
