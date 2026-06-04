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
 * Heurística (validada na prática — antes monitorávamos só status enum):
 *
 *   🟢 OPERACIONAL — status=CONECTADA E última msg recebida há <= 30min
 *   🟡 INSTÁVEL    — status=CONECTADA mas última msg há 30min-3h
 *                    (suspeita de "sessão zumbi": Evolution diz OK mas
 *                     WhatsApp parou de enviar eventos)
 *   🔴 OFFLINE     — status != CONECTADA OU sem msg há > 3h
 *
 * As janelas (30min, 3h) foram escolhidas considerando que mesmo restaurantes
 * com baixo volume recebem ALGUMA interação ao longo do dia. Sem nada por
 * mais de 3h sinaliza zumbi com alta confiança.
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

    private static final int MIN_OPERACIONAL = 30;     // até 30min sem msg = OK
    private static final int MIN_INSTAVEL    = 180;    // 30min-3h = INSTAVEL
                                                       // > 3h ou status fora = OFFLINE

    /** Calcula estado atual sem persistir. */
    public WhatsappHealthLog.Estado avaliarEstado(WhatsappInstance inst) {
        if (inst.getStatus() != WhatsappInstance.Status.CONECTADA) {
            return WhatsappHealthLog.Estado.OFFLINE;
        }
        Integer minSemMsg = minutosSemMensagem(inst);
        if (minSemMsg == null) return WhatsappHealthLog.Estado.INSTAVEL; // nunca recebeu nada
        if (minSemMsg <= MIN_OPERACIONAL) return WhatsappHealthLog.Estado.OPERACIONAL;
        if (minSemMsg <= MIN_INSTAVEL)    return WhatsappHealthLog.Estado.INSTAVEL;
        return WhatsappHealthLog.Estado.OFFLINE;
    }

    public Integer minutosSemMensagem(WhatsappInstance inst) {
        LocalDateTime ultima = inst.getUltimaMensagemRecebidaEm();
        if (ultima == null) return null;
        return (int) Duration.between(ultima, LocalDateTime.now()).toMinutes();
    }

    /** Snapshot atual completo pro frontend (admin + dono do restaurante). */
    public Map<String, Object> resumoAtual(WhatsappInstance inst) {
        Map<String, Object> r = new LinkedHashMap<>();
        WhatsappHealthLog.Estado estado = avaliarEstado(inst);
        r.put("estado", estado.name());
        r.put("status", inst.getStatus().name());
        r.put("ultimaMensagemRecebidaEm",
                inst.getUltimaMensagemRecebidaEm() == null ? null : inst.getUltimaMensagemRecebidaEm().toString());
        r.put("ultimaRespostaEnviadaEm",
                inst.getUltimaRespostaEnviadaEm() == null ? null : inst.getUltimaRespostaEnviadaEm().toString());
        r.put("minutosSemMensagem", minutosSemMensagem(inst));
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
