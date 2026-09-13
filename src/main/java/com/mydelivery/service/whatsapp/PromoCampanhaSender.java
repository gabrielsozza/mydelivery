package com.mydelivery.service.whatsapp;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.PromoCampanha;
import com.mydelivery.model.PromoCampanhaEnvio;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.PromoCampanhaEnvioRepository;
import com.mydelivery.repository.PromoCampanhaRepository;
import com.mydelivery.repository.WhatsappInstanceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Job de envio das campanhas — roda a cada 60 segundos e processa 1 envio
 * por instância por tick. Isso, combinado com o rate limit já existente no
 * {@link WhatsappService#enviarMensagem}, garante:
 *
 * <ul>
 *   <li><b>Cap por instância</b>: máx 40 msgs/hora broadcasts (janela deslizante)</li>
 *   <li><b>Cap diário</b>: máx 200 msgs/dia broadcasts por instância</li>
 *   <li><b>Delay entre envios</b>: mínimo 45s (com jitter até 60s adicionais)</li>
 *   <li><b>Janela permitida</b>: 09h-20h horário do servidor (evita madrugada)</li>
 *   <li><b>Personalização</b>: prefixa "Olá {nome}," pra criar variance de conteúdo
 *       (dois números vizinhos recebendo msgs idênticas dispara heurística de spam)</li>
 *   <li><b>Rodapé de opt-out</b>: sempre aparece — abre o caminho pro cliente
 *       responder PARE, que o {@link WhatsappBotService} registra automaticamente
 *       (não implementado nesse commit, ver TODO no bot).</li>
 * </ul>
 *
 * O rate limit por instância do próprio {@link WhatsappService} (60 msg/hora total,
 * incluindo respostas do bot) já cobre uma camada — este job adiciona um cap MENOR
 * exclusivo pra broadcast pra deixar sempre folga pro bot orgânico.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PromoCampanhaSender {

    private final PromoCampanhaRepository campanhaRepo;
    private final PromoCampanhaEnvioRepository envioRepo;
    private final WhatsappInstanceRepository instanceRepo;
    private final WhatsappService whatsappService;

    /** Cap horário conservador — bem abaixo do limite orgânico (60/h) pra deixar
     *  espaço pro bot responder pedidos ao mesmo tempo. */
    private static final int CAP_BROADCAST_HORA = 40;
    /** Cap diário conservador — WhatsApp geralmente tolera 300-500/dia sem ban,
     *  200 dá margem forte. */
    private static final int CAP_BROADCAST_DIA = 200;

    /** Só dispara entre 09h e 20h no horário do servidor — evita madrugada
     *  (queixa comum de spam de restaurante = "recebi promo às 3h da manhã"). */
    private static final LocalTime HORA_INICIO = LocalTime.of(9, 0);
    private static final LocalTime HORA_FIM = LocalTime.of(20, 0);

    /** Delay mínimo entre envios da mesma instância (segundos). */
    private static final int DELAY_MIN_S = 45;
    /** Delay adicional randomizado (segundos). Total: 45-105s por envio. */
    private static final int DELAY_JITTER_S = 60;

    /** Janelas anti-cap: instanceName → [timestampMs de cada envio nas últimas 24h].
     *  Cresce só com envios reais — reset a cada wipe de instância. */
    private final Map<String, List<Long>> historicoBroadcast = new ConcurrentHashMap<>();

    /** Próximo timestamp permitido pra cada instância (implementa o delay randomizado). */
    private final Map<String, Long> proximoEnvioPermitido = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void processar() {
        LocalTime agora = LocalTime.now();
        if (agora.isBefore(HORA_INICIO) || agora.isAfter(HORA_FIM)) {
            return;
        }
        List<PromoCampanha> ativas = campanhaRepo.findByStatus(PromoCampanha.Status.ENVIANDO);
        if (ativas.isEmpty()) return;

        long agoraMs = System.currentTimeMillis();
        for (PromoCampanha c : ativas) {
            try {
                processarCampanha(c, agoraMs);
            } catch (Exception e) {
                log.warn("[PromoSender] Erro ao processar campanha {}: {}", c.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    protected void processarCampanha(PromoCampanha campanha, long agoraMs) {
        Optional<WhatsappInstance> instOpt = instanceRepo.findByRestauranteId(campanha.getRestaurante().getId());
        if (instOpt.isEmpty() || instOpt.get().getStatus() != WhatsappInstance.Status.CONECTADA) {
            // Instância desconectou no meio — deixa em ENVIANDO pra retomar quando
            // reconectar. Se o dono não voltar, campanha fica ali até ele cancelar
            // manualmente. Log um warning por tick pra ter rastro.
            log.debug("[PromoSender] Campanha {} pausada de fato: instância não conectada.", campanha.getId());
            return;
        }
        WhatsappInstance inst = instOpt.get();
        String instName = inst.getInstanceName();

        // Delay mínimo entre envios
        Long proximo = proximoEnvioPermitido.get(instName);
        if (proximo != null && agoraMs < proximo) return;

        // Cap horário e diário
        if (excedeuCapHorario(instName, agoraMs) || excedeuCapDiario(instName, agoraMs)) {
            log.debug("[PromoSender] Cap atingido pra {} — aguardando janela abrir.", instName);
            return;
        }

        // Pega o próximo pendente
        List<PromoCampanhaEnvio> pendentes = envioRepo.findPendentes(campanha.getId(), PageRequest.of(0, 1));
        if (pendentes.isEmpty()) {
            // Acabou — marca campanha concluída
            campanha.setStatus(PromoCampanha.Status.CONCLUIDA);
            campanha.setConcluidaEm(LocalDateTime.now());
            campanhaRepo.save(campanha);
            log.info("[PromoSender] Campanha {} CONCLUÍDA — enviados={} falhas={}",
                    campanha.getId(), campanha.getTotalEnviados(), campanha.getTotalFalhas());
            return;
        }

        PromoCampanhaEnvio envio = pendentes.get(0);
        String textoPersonalizado = montarTextoPersonalizado(campanha.getTexto(), envio.getNomeCliente());
        try {
            whatsappService.enviarMensagem(inst, envio.getTelefone(), textoPersonalizado);
            envio.setEnviadoEm(LocalDateTime.now());
            envio.setSucesso(true);
            envioRepo.save(envio);
            campanha.setTotalEnviados(campanha.getTotalEnviados() + 1);
            registrarEnvio(instName, agoraMs);
            log.info("[PromoSender] campanha={} enviou pra {}*** (progresso {}/{})",
                    campanha.getId(),
                    envio.getTelefone().length() > 5 ? envio.getTelefone().substring(0, 5) : "?",
                    campanha.getTotalEnviados(), campanha.getTotalDestinos());
        } catch (Exception e) {
            envio.setEnviadoEm(LocalDateTime.now());
            envio.setSucesso(false);
            envio.setErroDetalhe(e.getMessage() == null ? "erro" : e.getMessage().substring(0, Math.min(300, e.getMessage().length())));
            envioRepo.save(envio);
            campanha.setTotalFalhas(campanha.getTotalFalhas() + 1);
            log.warn("[PromoSender] Falha ao enviar pra {} campanha={}: {}",
                    envio.getTelefone(), campanha.getId(), e.getMessage());
        }
        campanhaRepo.save(campanha);

        // Agenda próximo envio dessa instância com jitter
        int jitter = ThreadLocalRandom.current().nextInt(0, DELAY_JITTER_S + 1);
        proximoEnvioPermitido.put(instName, agoraMs + (DELAY_MIN_S + jitter) * 1000L);
    }

    /** Adiciona saudação personalizada + rodapé de opt-out ao texto do dono.
     *  Objetivo duplo: (1) variar texto entre destinos (anti spam detection);
     *  (2) dar ao cliente o "PARE" no rodapé como escape. */
    private String montarTextoPersonalizado(String textoOriginal, String nomeCliente) {
        String saudacao;
        if (nomeCliente != null && !nomeCliente.isBlank()) {
            String primeiroNome = nomeCliente.trim().split("\\s+")[0];
            saudacao = "Olá " + primeiroNome + ",";
        } else {
            saudacao = "Olá!";
        }
        return saudacao + "\n\n" + textoOriginal.trim()
                + "\n\n_Se não quiser mais receber promoções, responda PARE._";
    }

    private void registrarEnvio(String instName, long agoraMs) {
        historicoBroadcast.computeIfAbsent(instName, k -> new ArrayList<>()).add(agoraMs);
    }

    private boolean excedeuCapHorario(String instName, long agoraMs) {
        List<Long> hist = historicoBroadcast.get(instName);
        if (hist == null) return false;
        long limite = agoraMs - 3_600_000L;
        synchronized (hist) {
            hist.removeIf(t -> t < limite - 86_400_000L); // limpa >24h de trás pra evitar leak
            long recentes = hist.stream().filter(t -> t >= limite).count();
            return recentes >= CAP_BROADCAST_HORA;
        }
    }

    private boolean excedeuCapDiario(String instName, long agoraMs) {
        List<Long> hist = historicoBroadcast.get(instName);
        if (hist == null) return false;
        long limite = agoraMs - 86_400_000L;
        synchronized (hist) {
            long ultimo24h = hist.stream().filter(t -> t >= limite).count();
            return ultimo24h >= CAP_BROADCAST_DIA;
        }
    }
}
