package com.mydelivery.service.whatsapp;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.config.EvolutionProperties;
import com.mydelivery.model.Restaurante;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappAcaoAutomaticaRepository;
import com.mydelivery.repository.WhatsappHealthLogRepository;
import com.mydelivery.repository.WhatsappIncidenteRepository;
import com.mydelivery.repository.WhatsappInstanceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orquestra o ciclo de vida da instância WhatsApp por restaurante (multi-tenant).
 *
 * Nome da instância é determinístico: "mydelivery-rest-{restauranteId}". Permite
 * reconectar sem perder a sessão se o backend reiniciar, e isola tenants.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsappService {

    private final WhatsappInstanceRepository repo;
    private final WhatsappHealthLogRepository healthLogRepo;
    private final EvolutionClient evolutionClient;
    private final EvolutionProperties props;

    /**
     * Lock por restauranteId pra evitar que múltiplas threads do polling do
     * frontend (a cada 4s) ou threads paralelas do job de reconnect entrem
     * simultaneamente no auto-recovery e criem múltiplas instâncias na
     * Evolution. O caso real observado: 3 threads exec-1/3/4 rodaram o
     * status() em paralelo, cada uma criou um nome diferente
     * (...8448 / ...8461 / ...8465), e os webhooks chegavam para nomes que
     * o banco local já tinha sobrescrito → "instância não encontrada
     * localmente — descartando evento". Resultado: QR nunca chegava.
     */
    private static final ConcurrentHashMap<Long, Object> autoRecoveryLocks = new ConcurrentHashMap<>();
    // Injeção via field pra evitar problema de ordem de bootstrap.
    // IncidenteService não depende de WhatsappService, então não há ciclo,
    // mas usar field aqui mantém o construtor enxuto e permite null safety.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WhatsappIncidenteService incidenteService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WhatsappIncidenteRepository incidenteRepoOpt;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WhatsappAcaoAutomaticaRepository acaoRepoOpt;

    /**
     * Conecta o WhatsApp do restaurante. Idempotente:
     *  - Sem instância local → cria na Evolution + persiste.
     *  - Já existe local mas DESCONECTADA → re-conecta (busca novo QR).
     *  - Já CONECTADA → devolve estado atual (sem QR).
     */
    @Transactional
    public WhatsappInstance conectar(Restaurante restaurante) {
        WhatsappInstance inst = repo.findByRestauranteId(restaurante.getId()).orElse(null);

        if (inst == null) {
            // Race condition: o job de reconnect, outra aba do painel ou um POST
            // duplicado podem ter criado a instância em paralelo. O findByRestauranteId
            // não enxergou porque outra transaction ainda não commitou no momento da
            // leitura, mas quando o INSERT do criarNova roda, o índice unique
            // (idx_wa_restaurante em restaurante_id) já está ocupado e estoura
            // DataIntegrityViolationException → vira HTTP 400 no GlobalExceptionHandler.
            // Fix: catch a violação e re-fetch a linha que a outra transaction criou.
            try {
                inst = criarNova(restaurante);
            } catch (DataIntegrityViolationException dive) {
                log.warn("[WhatsApp] Concorrência ao criar instância pra rest_id={} — re-fetch da linha existente",
                        restaurante.getId());
                inst = repo.findByRestauranteId(restaurante.getId())
                        .orElseThrow(() -> new RuntimeException(
                                "Falha ao criar instância WhatsApp (constraint violada mas linha ausente)"));
            }
        }

        // Refresca status atual da Evolution antes de decidir o que fazer
        atualizarStatusDaEvolution(inst);
        repo.save(inst);

        if (inst.getStatus() == WhatsappInstance.Status.CONECTADA) {
            return inst; // nada a fazer
        }

        // Busca/renova QR — com 1 retry curto pra cobrir Evolution v2.x que
        // frequentemente responde {"count":0} na 1ª chamada e cospe o QR
        // 800-1500ms depois. Mudança CIRÚRGICA pra reduzir caso "QR demora a
        // aparecer". Mantém a lógica antiga de fallback via webhook.
        try {
            String qr = tentarObterQrAgora(inst.getInstanceName());
            if (qr != null) {
                inst.setQrCode(qr);
                inst.setQrExpiraEm(LocalDateTime.now().plusSeconds(60));
            }
            // Em Evolution v2.1.x o /connect às vezes responde {"count":0} sem QR
            // — ele chega depois via webhook QRCODE_UPDATED. Marcamos AGUARDANDO_QR
            // de qualquer forma pra o frontend começar o polling em /status.
            inst.setStatus(WhatsappInstance.Status.AGUARDANDO_QR);
        } catch (RuntimeException e) {
            log.error("[WhatsApp] Erro ao gerar QR pra {}: {}", inst.getInstanceName(), e.getMessage());
            inst.setStatus(WhatsappInstance.Status.ERRO);
        }

        return repo.save(inst);
    }

    /**
     * Tenta obter QR da Evolution com até 2 chamadas:
     *  - 1ª chamada
     *  - Se não veio QR, pausa 800ms e tenta de novo (Evolution v2.x cospe
     *    o QR ~1s após o /connect inicial em instâncias recém-criadas)
     *
     * Total max ~1s adicional na resposta. Retorna null se nenhuma retornou QR
     * (caller marca AGUARDANDO_QR e webhook QRCODE_UPDATED chega depois).
     *
     * Lança RuntimeException se a Evolution está fora do ar (caller cuida).
     */
    @SuppressWarnings("unchecked")
    private String tentarObterQrAgora(String instanceName) {
        Map<String, Object> resp = evolutionClient.conectar(instanceName);
        String qr = extrairQrCode(resp);
        if (qr != null) return qr;
        try { Thread.sleep(800L); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        resp = evolutionClient.conectar(instanceName);
        return extrairQrCode(resp);
    }

    /**
     * Salva QR Code recebido via webhook QRCODE_UPDATED da Evolution v2.1.x.
     * Chamado pelo WhatsappWebhookController quando o evento chega.
     */
    @Transactional
    public void salvarQrCode(WhatsappInstance inst, String qrBase64) {
        if (qrBase64 == null || qrBase64.isBlank()) return;
        inst.setQrCode(qrBase64);
        inst.setQrExpiraEm(LocalDateTime.now().plusSeconds(60));
        inst.setStatus(WhatsappInstance.Status.AGUARDANDO_QR);
        repo.save(inst);
        log.info("[WhatsApp] QR atualizado via webhook pra {}", inst.getInstanceName());
    }

    /** Polling do frontend pra detectar conexão. Atualiza status local antes de devolver. */
    @Transactional
    public WhatsappInstance status(Restaurante restaurante) {
        WhatsappInstance inst = repo.findByRestauranteId(restaurante.getId()).orElse(null);
        if (inst == null) {
            return WhatsappInstance.builder()
                    .restaurante(restaurante)
                    .instanceName(nomeInstancia(restaurante))
                    .status(WhatsappInstance.Status.NOVA)
                    .build();
        }
        atualizarStatusDaEvolution(inst);

        // AUTO-RECOVERY: usuário relatava QR ficando eternamente em spinner. Causa:
        // Evolution às vezes não dispara QRCODE_UPDATED depois de /connect numa
        // instância recém-criada (webhook não chega, ou sessão fica em "connecting"
        // sem emitir QR). Se passamos do tempo de expiração esperado SEM QR válido,
        // re-disparamos /connect — Evolution gera QR novo e o webhook deve cuspir.
        //
        // CRITICAL: tudo aqui dentro precisa rodar 1 thread por vez por restaurante,
        // senão o polling do frontend (4s) cria múltiplas instâncias paralelas na
        // Evolution e o webhook QR fica sendo descartado por nome inconsistente.
        if (inst.getStatus() == WhatsappInstance.Status.AGUARDANDO_QR
                || inst.getStatus() == WhatsappInstance.Status.NOVA) {
            boolean qrAusente = inst.getQrCode() == null || inst.getQrCode().isBlank();
            boolean qrExpirado = inst.getQrExpiraEm() != null
                    && LocalDateTime.now().isAfter(inst.getQrExpiraEm());
            if (qrAusente || qrExpirado) {
                Object lock = autoRecoveryLocks.computeIfAbsent(restaurante.getId(), k -> new Object());
                synchronized (lock) {
                    // Re-fetch dentro do lock pra pegar atualização de outra thread que
                    // acabou de terminar — evita disparar /connect logo após outra thread
                    // ter setado o qrCode.
                    WhatsappInstance fresh = repo.findByRestauranteId(restaurante.getId()).orElse(null);
                    if (fresh != null) inst = fresh;
                    qrAusente = inst.getQrCode() == null || inst.getQrCode().isBlank();
                    qrExpirado = inst.getQrExpiraEm() != null
                            && LocalDateTime.now().isAfter(inst.getQrExpiraEm());
                    if (!qrAusente && !qrExpirado) {
                        return repo.save(inst);
                    }
                try {
                    log.info("[WhatsApp] QR ausente/expirado pra {} — forçando /connect novamente",
                            inst.getInstanceName());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resp = evolutionClient.conectar(inst.getInstanceName());
                    String qr = extrairQrCode(resp);
                    if (qr != null) {
                        inst.setQrCode(qr);
                        inst.setQrExpiraEm(LocalDateTime.now().plusSeconds(60));
                        inst.setStatus(WhatsappInstance.Status.AGUARDANDO_QR);
                        log.info("[WhatsApp] QR re-obtido sincronamente pra {}", inst.getInstanceName());
                    } else {
                        // FALLBACK ANTI-WEBHOOK-PERDIDO: a Evolution v2.x responde
                        // /connect com {"count":0} quando a sessão já está "connecting"
                        // — espera-se que o QR chegue por webhook QRCODE_UPDATED. Se o
                        // webhook foi perdido (Railway em deploy, instabilidade de rede,
                        // Evolution não reemitir), o QR fica preso pra sempre — usuário
                        // só consegue destravar via "Resetar e recomeçar" manualmente.
                        // Solução: detectamos esse caso e fazemos o reset automático com
                        // throttle de 25s pra não loopar. Renomeia a instância (sufixo
                        // timestamp) pra obrigar Evolution a emitir QR fresh.
                        boolean podeAutoReset = inst.getUltimaTentativaReconexaoEm() == null
                                || LocalDateTime.now().isAfter(
                                        inst.getUltimaTentativaReconexaoEm().plusSeconds(25));
                        if (podeAutoReset) {
                            inst.setUltimaTentativaReconexaoEm(LocalDateTime.now());
                            log.warn("[WhatsApp] /connect sem QR pra {} — forçando recriação com nome novo",
                                    inst.getInstanceName());
                            try { evolutionClient.logout(inst.getInstanceName()); }
                            catch (RuntimeException ignore) { /* sessão pode já estar fechada */ }
                            try { evolutionClient.deletar(inst.getInstanceName()); }
                            catch (RuntimeException ignore) { /* idem */ }
                            String nomeBase = inst.getInstanceName().replaceAll("-\\d+$", "");
                            String novoNome = nomeBase + "-" + (System.currentTimeMillis() / 1000);
                            String webhookUrl = props.getWebhookBaseUrl()
                                    + "/api/webhooks/whatsapp/" + novoNome;
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> respNova = evolutionClient.criarInstancia(novoNome, webhookUrl);
                                inst.setInstanceName(novoNome);
                                String tk = extrairInstanceToken(respNova);
                                if (tk != null) inst.setInstanceToken(tk);
                                String qrNovo = extrairQrCode(respNova);
                                if (qrNovo != null) {
                                    inst.setQrCode(qrNovo);
                                    inst.setQrExpiraEm(LocalDateTime.now().plusSeconds(60));
                                    log.info("[WhatsApp] QR obtido na recriação forçada pra {}", novoNome);
                                } else {
                                    log.info("[WhatsApp] Recriação OK ({}). QR virá por webhook.", novoNome);
                                }
                                inst.setStatus(WhatsappInstance.Status.AGUARDANDO_QR);
                            } catch (RuntimeException e2) {
                                log.error("[WhatsApp] Recriação forçada falhou: {}", e2.getMessage());
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                    log.warn("[WhatsApp] re-tentativa /connect falhou pra {}: {}",
                            inst.getInstanceName(), e.getMessage());
                    // Instância sumiu na Evolution (deletada manualmente, reset do servidor,
                    // ou nunca foi criada com sucesso). Recria IN-PLACE no mesmo registro
                    // — NÃO deleta a linha porque whatsapp_health_log tem FK pra cá sem
                    // ON DELETE CASCADE, e DELETE quebraria com constraint violation.
                    if (msg.contains("not found") || msg.contains("does not exist")
                            || msg.contains("404")) {
                        log.warn("[WhatsApp] instância {} sumiu da Evolution — recriando in-place",
                                inst.getInstanceName());
                        String webhookUrl = props.getWebhookBaseUrl()
                                + "/api/webhooks/whatsapp/" + inst.getInstanceName();
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> resp2 = evolutionClient.criarInstancia(
                                    inst.getInstanceName(), webhookUrl);
                            String tk = extrairInstanceToken(resp2);
                            if (tk != null) inst.setInstanceToken(tk);
                            String qr2 = extrairQrCode(resp2);
                            if (qr2 != null) {
                                inst.setQrCode(qr2);
                                inst.setQrExpiraEm(LocalDateTime.now().plusSeconds(60));
                            }
                            inst.setStatus(WhatsappInstance.Status.AGUARDANDO_QR);
                            log.info("[WhatsApp] recriada in-place. QR={}", qr2 != null ? "ok" : "aguardando webhook");
                        } catch (RuntimeException e2) {
                            log.error("[WhatsApp] recriação in-place falhou: {}", e2.getMessage());
                        }
                    }
                }
                } // end synchronized (lock)
            }
        }

        return repo.save(inst);
    }

    /**
     * Diagnóstico cru: devolve tudo que a Evolution está dizendo sobre essa instância
     * agora, sem cache nosso. Útil pra distinguir "Evolution travada" de "WhatsApp baniu"
     * de "instância nunca foi criada de verdade".
     */
    @Transactional(readOnly = true)
    public Map<String, Object> diagnostico(Restaurante restaurante) {
        WhatsappInstance inst = repo.findByRestauranteId(restaurante.getId()).orElse(null);
        Map<String, Object> out = new java.util.HashMap<>();
        if (inst == null) {
            out.put("local", "sem registro");
            return out;
        }
        out.put("instanceName", inst.getInstanceName());
        out.put("statusLocal", inst.getStatus() == null ? null : inst.getStatus().name());
        out.put("temQrCodeLocal", inst.getQrCode() != null && !inst.getQrCode().isBlank());
        out.put("qrExpiraEm", inst.getQrExpiraEm() == null ? null : inst.getQrExpiraEm().toString());
        out.put("ultimaMsgRecebidaEm", inst.getUltimaMensagemRecebidaEm() == null ? null
                : inst.getUltimaMensagemRecebidaEm().toString());

        // 1. Estado conexão na Evolution
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> state = evolutionClient.consultarStatus(inst.getInstanceName());
            out.put("evolutionConnectionState", state);
        } catch (RuntimeException e) {
            out.put("evolutionConnectionStateErro", e.getMessage());
        }

        // 2. Webhook configurado
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> wh = evolutionClient.consultarWebhook(inst.getInstanceName());
            out.put("evolutionWebhook", wh);
        } catch (RuntimeException e) {
            out.put("evolutionWebhookErro", e.getMessage());
        }

        // 3. Tenta gerar QR agora
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> qr = evolutionClient.conectar(inst.getInstanceName());
            Map<String, Object> resumo = new java.util.HashMap<>();
            resumo.put("temBase64", extrairQrCode(qr) != null);
            resumo.put("keys", qr == null ? null : qr.keySet());
            out.put("evolutionConnectResumo", resumo);
        } catch (RuntimeException e) {
            out.put("evolutionConnectErro", e.getMessage());
        }

        out.put("webhookConfiguradoNoBackend",
                props.getWebhookBaseUrl() + "/api/webhooks/whatsapp/" + inst.getInstanceName());

        return out;
    }

    /** Logout (mantém a instância — pode reconectar gerando novo QR). */
    @Transactional
    public void desconectar(Restaurante restaurante) {
        repo.findByRestauranteId(restaurante.getId()).ifPresent(inst -> {
            try {
                evolutionClient.logout(inst.getInstanceName());
            } catch (RuntimeException e) {
                log.warn("[WhatsApp] Logout falhou (continua marcando DESCONECTADA): {}", e.getMessage());
            }
            inst.setStatus(WhatsappInstance.Status.DESCONECTADA);
            inst.setQrCode(null);
            inst.setQrExpiraEm(null);
            repo.save(inst);
        });
    }

    /**
     * Reinicia a sessão WebSocket da instância (NÃO precisa de QR novo). Refresca
     * o Baileys do lado da Evolution — útil pra mitigar shadow-ban silencioso do
     * WhatsApp, que para de entregar mensagens depois de horas mesmo com a sessão
     * aparentemente "open".
     *
     * Idempotente: se a instância não estava conectada, Evolution ignora.
     */
    public void restart(Restaurante restaurante) {
        repo.findByRestauranteId(restaurante.getId()).ifPresent(inst -> {
            try {
                evolutionClient.restart(inst.getInstanceName());
                log.info("[WhatsApp] restart manual ok — instância={}", inst.getInstanceName());
            } catch (RuntimeException e) {
                log.warn("[WhatsApp] restart falhou pra {}: {}", inst.getInstanceName(), e.getMessage());
                throw new RuntimeException("Não consegui reiniciar a sessão. Tente reconectar manualmente.");
            }
        });
    }

    /**
     * Reset COMPLETO da instância: tenta logout + delete na Evolution e apaga o registro local.
     * Próximo /conectar cria instância nova do zero. Use quando a instância está em estado zumbi
     * (Evolution reporta "open" mas WhatsApp não entrega mensagens — shadow-ban).
     */
    @Transactional
    public void resetar(Restaurante restaurante) {
        // RESET TOTAL: além da instância "atual" no banco, varremos a Evolution
        // procurando TODAS as instâncias com prefix "mydelivery-rest-{id}-" e
        // deletamos todas. Isso é necessário porque o bug bf61fcf (corrigido em
        // f29d49e) chegou a criar várias instâncias órfãs na Evolution
        // (...-1780788448, ...-1780788461, ...-1780788465 nos logs). O reset
        // antigo só deletava a "atual" no banco, as órfãs ficavam respondendo
        // ao /connect com {"count":0} e o QR nunca chegava.
        String prefix = "mydelivery-rest-" + restaurante.getId();
        try {
            java.util.List<Map<String, Object>> todas = evolutionClient.fetchInstances();
            for (Map<String, Object> ev : todas) {
                Object nObj = ev.get("name");
                if (nObj == null) nObj = ev.get("instanceName");
                String n = nObj == null ? null : nObj.toString();
                if (n != null && (n.equals(prefix) || n.startsWith(prefix + "-"))) {
                    try { evolutionClient.logout(n); } catch (RuntimeException ignore) {}
                    try { evolutionClient.deletar(n); }
                    catch (RuntimeException e) { log.warn("[WhatsApp] delete órfã {} falhou: {}", n, e.getMessage()); }
                    log.info("[WhatsApp] Órfã removida da Evolution: {}", n);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[WhatsApp] varredura de órfãs falhou (ok): {}", e.getMessage());
        }
        repo.findByRestauranteId(restaurante.getId()).ifPresent(inst -> {
            String nome = inst.getInstanceName();
            Long instId = inst.getId();
            // Tenta de novo a "atual" caso a varredura tenha perdido (fetchInstances pode
            // não listar instâncias em estado intermediário).
            try { evolutionClient.logout(nome); } catch (RuntimeException e) {
                log.warn("[WhatsApp] logout falhou no reset (ok): {}", e.getMessage());
            }
            try { evolutionClient.deletar(nome); } catch (RuntimeException e) {
                log.warn("[WhatsApp] delete falhou no reset (ok): {}", e.getMessage());
            }
            // whatsapp_health_log + whatsapp_incidentes + whatsapp_acoes_automaticas
            // têm FK pra whatsapp_instances sem ON DELETE CASCADE. Deleta os
            // dependentes ANTES da instância pra evitar foreign key violation.
            try {
                healthLogRepo.deleteByInstanceId(instId);
            } catch (RuntimeException e) {
                log.warn("[WhatsApp] limpeza de health_log falhou no reset (ok): {}", e.getMessage());
            }
            if (acaoRepoOpt != null) {
                try { acaoRepoOpt.deleteByInstanceId(instId); }
                catch (RuntimeException e) { log.warn("[WhatsApp] limpeza ações falhou: {}", e.getMessage()); }
            }
            if (incidenteRepoOpt != null) {
                try { incidenteRepoOpt.deleteByInstanceId(instId); }
                catch (RuntimeException e) { log.warn("[WhatsApp] limpeza incidentes falhou: {}", e.getMessage()); }
            }
            repo.delete(inst);
            log.info("[WhatsApp] Reset completo de {}", nome);
        });
    }

    /** Toggle do bot — uso futuro pela UI (1-clique no painel). */
    @Transactional
    public WhatsappInstance toggleBot(Restaurante restaurante, boolean ativo) {
        WhatsappInstance inst = repo.findByRestauranteId(restaurante.getId())
                .orElseThrow(() -> new RuntimeException("WhatsApp ainda não conectado"));
        inst.setBotAtivo(ativo);
        return repo.save(inst);
    }

    /**
     * Envia mensagem usando a instância do restaurante.
     * Usado pelo WhatsappBotService — não é endpoint público.
     *
     * @param delayMs ms de "digitando…" mostrado antes da msg (0 = imediato).
     */
    public void enviarMensagem(WhatsappInstance inst, String numeroDestino, String texto, int delayMs) {
        if (inst.getStatus() != WhatsappInstance.Status.CONECTADA) {
            log.warn("[WhatsApp] Pulando envio: instância {} não está conectada", inst.getInstanceName());
            return;
        }
        try {
            // Convenção interna: se a resposta do bot começa com "IMG::<url>::<caption>",
            // envia imagem com legenda em vez de texto puro. Resolve o "quadrado preto"
            // do preview do link na mensagem de cardápio — agora aparece a logo do
            // restaurante. Detalhe: split com limit=2 pra a caption poder conter "::".
            if (texto != null && texto.startsWith("IMG::")) {
                String semPrefix = texto.substring(5);
                int sep = semPrefix.indexOf("::");
                if (sep > 0) {
                    String url = semPrefix.substring(0, sep);
                    String caption = semPrefix.substring(sep + 2);
                    evolutionClient.enviarMidia(inst.getInstanceName(), inst.getInstanceToken(),
                            numeroDestino, url, caption, delayMs);
                    log.info("[WhatsApp] Mídia enviada — instância={}, para={}***", inst.getInstanceName(),
                            numeroDestino.length() > 5 ? numeroDestino.substring(0, 5) : numeroDestino);
                    return;
                }
            }
            evolutionClient.enviarTexto(inst.getInstanceName(), inst.getInstanceToken(),
                    numeroDestino, texto, delayMs);
            log.info("[WhatsApp] Mensagem enviada — instância={}, para={}***", inst.getInstanceName(),
                    numeroDestino.length() > 5 ? numeroDestino.substring(0, 5) : numeroDestino);
            // Heartbeat de envio — atualizado SÓ em sucesso. Combinado com
            // ultimaMensagemRecebidaEm prova que o bot está respondendo.
            marcarRespostaEnviada(inst);
        } catch (RuntimeException e) {
            log.error("[WhatsApp] Falha ao enviar: {}", e.getMessage());
            boolean fallbackOk = false;
            // Fallback: se enviar mídia falhou (URL inválida etc), manda só o caption como texto
            if (texto != null && texto.startsWith("IMG::")) {
                String semPrefix = texto.substring(5);
                int sep = semPrefix.indexOf("::");
                if (sep > 0) {
                    String fallback = semPrefix.substring(sep + 2);
                    try {
                        evolutionClient.enviarTexto(inst.getInstanceName(), inst.getInstanceToken(),
                                numeroDestino, fallback, delayMs);
                        marcarRespostaEnviada(inst);
                        fallbackOk = true;
                    } catch (RuntimeException e2) {
                        log.error("[WhatsApp] Fallback texto também falhou: {}", e2.getMessage());
                    }
                }
            }
            // Só abre incidente se TUDO falhou (envio original + eventual fallback).
            // Senão estaríamos abrindo incidente toda vez que cai no fallback de mídia.
            if (!fallbackOk && incidenteService != null) {
                try {
                    incidenteService.abrirSe(inst,
                            com.mydelivery.model.WhatsappIncidente.Tipo.ERRO_API_EVOLUTION,
                            com.mydelivery.model.WhatsappIncidente.Severidade.MEDIA,
                            "enviarMensagem falhou: " + e.getMessage(),
                            "{\"destino\":\"***\",\"len\":" + (texto == null ? 0 : texto.length()) + "}");
                } catch (Exception ignore) { /* incidente é best-effort, não bloqueia envio */ }
            }
        }
    }

    /** Overload sem delay — mantém callers antigos. */
    public void enviarMensagem(WhatsappInstance inst, String numeroDestino, String texto) {
        enviarMensagem(inst, numeroDestino, texto, 0);
    }

    // ── diagnóstico de webhook ──

    /** GET na Evolution pra ver qual URL/eventos estão configurados pra essa instância. */
    public Map<String, Object> diagWebhook(Restaurante restaurante) {
        WhatsappInstance inst = repo.findByRestauranteId(restaurante.getId())
                .orElseThrow(() -> new RuntimeException("WhatsApp ainda não conectado"));
        try {
            return evolutionClient.consultarWebhook(inst.getInstanceName());
        } catch (RuntimeException e) {
            log.warn("[WhatsApp] diagWebhook falhou: {}", e.getMessage());
            return Map.of("erro", e.getMessage());
        }
    }

    /**
     * Re-configura o webhook da Evolution apontando pra URL atual do backend.
     * Útil quando a URL salva ficou errada (env mudou após criar instância).
     */
    public Map<String, Object> resetWebhook(Restaurante restaurante) {
        WhatsappInstance inst = repo.findByRestauranteId(restaurante.getId())
                .orElseThrow(() -> new RuntimeException("WhatsApp ainda não conectado"));
        String webhookUrl = props.getWebhookBaseUrl() + "/api/webhooks/whatsapp/" + inst.getInstanceName();
        log.info("[WhatsApp] Re-configurando webhook de {} pra {}", inst.getInstanceName(), webhookUrl);
        return evolutionClient.definirWebhook(inst.getInstanceName(), webhookUrl);
    }

    // ── webhook helpers ──

    /** Lookup por nome de instância (usado pelo WebhookController). */
    public WhatsappInstance buscarPorNome(String instanceName) {
        return repo.findByInstanceName(instanceName).orElse(null);
    }

    /** Busca a instância do restaurante (ou null se não tem). Read-only. */
    @Transactional(readOnly = true)
    public WhatsappInstance buscar(Restaurante restaurante) {
        return repo.findByRestauranteId(restaurante.getId()).orElse(null);
    }

    /** Heartbeat FRACO — chamado pelo WhatsappWebhookController em CADA evento
     *  (inclusive CONNECTION_UPDATE periódico). Só prova "Evolution → backend"
     *  está vivo, NÃO que o bot funciona. */
    @Transactional
    public void marcarMensagemRecebida(WhatsappInstance inst) {
        try {
            inst.setUltimaMensagemRecebidaEm(java.time.LocalDateTime.now());
            if (inst.getTentativasReconexaoSeguidas() != null && inst.getTentativasReconexaoSeguidas() > 0) {
                inst.setTentativasReconexaoSeguidas(0);
            }
            repo.save(inst);
        } catch (Exception e) {
            log.warn("[WhatsApp] Falha ao atualizar heartbeat fraco: {}", e.getMessage());
        }
    }

    /** Heartbeat FORTE — chamado SÓ em MESSAGES_UPSERT real de cliente
     *  (já filtrado fromMe=false + remoteJid válido). É o sinal que prova
     *  que o bot está realmente recebendo mensagens, não só os keep-alives
     *  internos da Evolution. */
    @Transactional
    public void marcarMensagemClienteRecebida(WhatsappInstance inst) {
        try {
            inst.setUltimaMensagemClienteEm(java.time.LocalDateTime.now());
            repo.save(inst);
        } catch (Exception e) {
            log.warn("[WhatsApp] Falha ao atualizar heartbeat forte: {}", e.getMessage());
        }
    }

    /** Heartbeat de envio — chamado por enviarMensagem em sucesso. */
    @Transactional
    public void marcarRespostaEnviada(WhatsappInstance inst) {
        try {
            inst.setUltimaRespostaEnviadaEm(java.time.LocalDateTime.now());
            repo.save(inst);
        } catch (Exception e) {
            log.warn("[WhatsApp] Falha ao atualizar heartbeat de envio: {}", e.getMessage());
        }
    }

    /** Atualizado pelo handler do webhook quando recebe CONNECTION_UPDATE. */
    @Transactional
    public void marcarConectada(WhatsappInstance inst, String phone) {
        inst.setStatus(WhatsappInstance.Status.CONECTADA);
        inst.setConectadoEm(LocalDateTime.now());
        inst.setQrCode(null);
        inst.setQrExpiraEm(null);
        if (phone != null && !phone.isBlank()) inst.setPhone(phone);
        repo.save(inst);
        log.info("[WhatsApp] Instância {} CONECTADA (phone={})", inst.getInstanceName(), phone);
    }

    @Transactional
    public void marcarDesconectada(WhatsappInstance inst) {
        // Evolution v2.1.x manda CONNECTION_UPDATE com state=close logo apos
        // criar a instancia (so dizendo "nao pareado ainda"). Esse evento
        // chega ANTES do QRCODE_UPDATED e nao deve sobrescrever o estado de
        // espera do QR. So marcamos DESCONECTADA se realmente estavamos
        // CONECTADA antes (cliente saiu do WhatsApp).
        if (inst.getStatus() != WhatsappInstance.Status.CONECTADA) {
            log.debug("[WhatsApp] Ignorando close em {} (status atual={}, nao estava pareada)",
                    inst.getInstanceName(), inst.getStatus());
            return;
        }
        inst.setStatus(WhatsappInstance.Status.DESCONECTADA);
        inst.setQrCode(null);
        inst.setQrExpiraEm(null);
        repo.save(inst);
        log.info("[WhatsApp] Instância {} DESCONECTADA via webhook", inst.getInstanceName());
    }

    // ── privados ──

    @SuppressWarnings("unchecked")
    private WhatsappInstance criarNova(Restaurante restaurante) {
        String nomeBase = nomeInstancia(restaurante);
        // Se Evolution rejeitar com "already in use", tenta com sufixo timestamp.
        // Acontece quando o /reset não conseguiu apagar a instância zumbi do lado
        // da Evolution. Nome novo = instância nova de verdade (com proxy correto).
        String nome = nomeBase;
        String webhookUrl = props.getWebhookBaseUrl() + "/api/webhooks/whatsapp/" + nome;
        Map<String, Object> resp = null;

        for (int tentativa = 0; tentativa < 2; tentativa++) {
            log.info("[WhatsApp] Criando instância {} (webhook={})", nome, webhookUrl);
            try {
                resp = evolutionClient.criarInstancia(nome, webhookUrl);
                break;
            } catch (RuntimeException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (tentativa == 0 && msg.toLowerCase().contains("already in use")) {
                    // Bumpa nome com sufixo timestamp e tenta de novo
                    nome = nomeBase + "-" + (System.currentTimeMillis() / 1000);
                    webhookUrl = props.getWebhookBaseUrl() + "/api/webhooks/whatsapp/" + nome;
                    log.warn("[WhatsApp] Nome em uso na Evolution. Re-tentando com {}", nome);
                    continue;
                }
                log.warn("[WhatsApp] criarInstancia falhou ({}). Tentando reusar via /connect.", msg);
                resp = Map.of();
                break;
            }
        }

        String token = extrairInstanceToken(resp);
        // Evolution v2.x já devolve o QR no body do /instance/create quando
        // qrcode=true (ver EvolutionClient.criarInstancia). Aproveitar isso
        // evita esperar 5-20s pelo webhook QRCODE_UPDATED, que às vezes
        // demora ou nem chega (rede entre Evolution e nosso backend).
        String qrInicial = extrairQrCode(resp);
        WhatsappInstance.Status statusInicial = qrInicial != null
                ? WhatsappInstance.Status.AGUARDANDO_QR
                : WhatsappInstance.Status.NOVA;
        LocalDateTime qrExpira = qrInicial != null
                ? LocalDateTime.now().plusSeconds(60)
                : null;
        if (qrInicial != null) {
            log.info("[WhatsApp] QR pré-extraído da resposta /instance/create para {}", nome);
        }

        return repo.save(WhatsappInstance.builder()
                .restaurante(restaurante)
                .instanceName(nome)
                .instanceToken(token)
                .qrCode(qrInicial)
                .qrExpiraEm(qrExpira)
                .status(statusInicial)
                .botAtivo(true)
                .build());
    }

    private String nomeInstancia(Restaurante r) {
        return "mydelivery-rest-" + r.getId();
    }

    /**
     * Atualiza o status local baseado no que a Evolution diz. Tolerante a falha:
     * se a Evolution está fora, mantém o estado atual em vez de marcar ERRO.
     */
    @SuppressWarnings("unchecked")
    private void atualizarStatusDaEvolution(WhatsappInstance inst) {
        try {
            Map<String, Object> resp = evolutionClient.consultarStatus(inst.getInstanceName());
            String state = extrairState(resp);
            if ("open".equalsIgnoreCase(state)) {
                // RESPEITA INTENÇÃO DO USUÁRIO: se o usuário acabou de clicar
                // "Desconectar" (status=DESCONECTADA), NÃO promove de volta pra
                // CONECTADA só porque a Evolution ainda mostra "open" — o logout
                // demora alguns segundos pra propagar no Baileys. Antes desse fix
                // a tela ficava presa em "Conectado" após desconectar.
                // Só auto-promove se a transição faz sentido: NOVA, AGUARDANDO_QR
                // ou ERRO indicam que estamos tentando subir; CONECTADA já está OK.
                if (inst.getStatus() == WhatsappInstance.Status.NOVA
                        || inst.getStatus() == WhatsappInstance.Status.AGUARDANDO_QR
                        || inst.getStatus() == WhatsappInstance.Status.ERRO) {
                    inst.setStatus(WhatsappInstance.Status.CONECTADA);
                    inst.setConectadoEm(LocalDateTime.now());
                    inst.setQrCode(null);
                    inst.setQrExpiraEm(null);
                }
            } else if ("close".equalsIgnoreCase(state)) {
                if (inst.getStatus() == WhatsappInstance.Status.CONECTADA) {
                    inst.setStatus(WhatsappInstance.Status.DESCONECTADA);
                }
            }
        } catch (RuntimeException e) {
            log.debug("[WhatsApp] consultarStatus falhou (silenciado): {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String extrairInstanceToken(Map<String, Object> resp) {
        if (resp == null) return null;
        // Evolution v2 devolve em hash.apikey
        Object hash = resp.get("hash");
        if (hash instanceof Map<?, ?> m) {
            Object k = ((Map<String, Object>) m).get("apikey");
            if (k != null) return k.toString();
        }
        Object direct = resp.get("apikey");
        return direct != null ? direct.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private String extrairQrCode(Map<String, Object> resp) {
        if (resp == null) return null;
        // Evolution v1.x e algumas v2.x devolvem no root
        Object b64 = resp.get("base64");
        if (b64 != null) return b64.toString();
        Object code = resp.get("code");
        if (code != null) return code.toString();
        // Evolution v2.1.x envolve dentro de um objeto "qrcode"
        Object qrcode = resp.get("qrcode");
        if (qrcode instanceof Map<?, ?> m) {
            Map<String, Object> q = (Map<String, Object>) m;
            Object qb64 = q.get("base64");
            if (qb64 != null) return qb64.toString();
            Object qcode = q.get("code");
            if (qcode != null) return qcode.toString();
        }
        return null;
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
}
