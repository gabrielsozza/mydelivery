package com.mydelivery.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mydelivery.model.Restaurante;
import com.mydelivery.model.WhatsappHealthLog;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.repository.WhatsappDesconexaoLogRepository;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.WhatsappHealthService;
import com.mydelivery.service.whatsapp.WhatsappService;

import lombok.RequiredArgsConstructor;

/**
 * Endpoints de saúde do WhatsApp.
 *
 *  - Restaurante (auth RESTAURANTE):
 *     GET  /api/restaurante/whatsapp/saude
 *     POST /api/restaurante/whatsapp/saude/reconectar
 *
 *  - Internos (X-Admin-Secret, chamados pelo admin-api):
 *     GET  /api/admin-internal/whatsapp/{instanceName}/saude
 *     GET  /api/admin-internal/whatsapp/{instanceName}/historico
 *     POST /api/admin-internal/whatsapp/{instanceName}/reconectar
 */
@RestController
@RequiredArgsConstructor
public class WhatsappHealthController {

    private final RestauranteRepository restauranteRepo;
    private final WhatsappInstanceRepository instanceRepo;
    private final WhatsappDesconexaoLogRepository desconexaoLogRepo;
    private final WhatsappHealthService healthService;
    private final WhatsappService whatsappService;

    @Value("${mydelivery.admin.internal-secret:${ADMIN_INTERNAL_SECRET:}}")
    private String adminSecret;

    // ──────────── Endpoints do RESTAURANTE ────────────

    @GetMapping("/api/restaurante/whatsapp/saude")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> saudeRestaurante(@AuthenticationPrincipal String email) {
        WhatsappInstance inst = instanciaDoUsuario(email);
        if (inst == null) {
            return ResponseEntity.ok(Map.of("estado", "OFFLINE", "status", "NOVA",
                    "mensagem", "Instância WhatsApp não criada"));
        }
        return ResponseEntity.ok(healthService.resumoAtual(inst));
    }

    @PostMapping("/api/restaurante/whatsapp/saude/reconectar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> reconectarRestaurante(@AuthenticationPrincipal String email) {
        WhatsappInstance inst = instanciaDoUsuario(email);
        if (inst == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Instância não encontrada");
        boolean ok = healthService.tentarReconectar(inst);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", ok);
        r.put("estadoAtual", healthService.avaliarEstado(inst).name());
        r.put("mensagem", ok
                ? "Reconexão disparada. Aguarde até 1min e veja se voltou."
                : "Falha ao chamar a Evolution. Sessão pode precisar de novo QR.");
        return ResponseEntity.ok(r);
    }

    private WhatsappInstance instanciaDoUsuario(String email) {
        Restaurante r = restauranteRepo.findByUsuarioEmail(email)
                .orElseThrow(() -> new RuntimeException("Restaurante não encontrado"));
        return instanceRepo.findByRestauranteId(r.getId()).orElse(null);
    }

    // ──────────── Endpoints INTERNOS (admin-api → main-api) ────────────

    @GetMapping("/api/admin-internal/whatsapp/{instanceName}/saude")
    public ResponseEntity<Map<String, Object>> saudeAdmin(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        WhatsappInstance inst = instanceRepo.findByInstanceName(instanceName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Map<String, Object> out = new LinkedHashMap<>(healthService.resumoAtual(inst));
        out.put("instanceId", inst.getId());
        out.put("instanceName", inst.getInstanceName());
        out.put("phone", inst.getPhone());
        return ResponseEntity.ok(out);
    }

    /**
     * Contadores ATUAIS por estado — pro topo do dashboard admin.
     * Retorna { conectados, aguardandoConexao, comProblema, total }.
     * Distingue fluxo NORMAL (esperando QR, manual) de PROBLEMA (queda inesperada).
     */
    @GetMapping("/api/admin-internal/whatsapp/contadores")
    public ResponseEntity<Map<String, Object>> contadores(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        var todas = instanceRepo.findAll();
        int conectados = 0;
        int aguardandoQr = 0;
        int desconectadosManual = 0;
        int comProblema = 0;
        for (var inst : todas) {
            var estado = healthService.avaliarEstado(inst);
            switch (estado) {
                case OPERACIONAL -> conectados++;
                case AGUARDANDO_CONEXAO -> {
                    if (inst.getStatus() == WhatsappInstance.Status.AGUARDANDO_QR
                            || inst.getStatus() == WhatsappInstance.Status.NOVA) {
                        aguardandoQr++;
                    } else {
                        desconectadosManual++;
                    }
                }
                case INSTAVEL, OFFLINE -> comProblema++;
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("total", todas.size());
        out.put("conectados", conectados);
        out.put("aguardandoQr", aguardandoQr);
        out.put("desconectadosManual", desconectadosManual);
        out.put("comProblema", comProblema);
        return ResponseEntity.ok(out);
    }

    /**
     * Saúde GLOBAL — agregado horário das últimas N horas (default 12),
     * mostrando quantos restaurantes estavam em cada estado por hora.
     * Usado pelo gráfico do dashboard admin.
     */
    @GetMapping("/api/admin-internal/whatsapp/saude-global")
    public ResponseEntity<List<Map<String, Object>>> saudeGlobal(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "12") int horas,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        if (horas < 1) horas = 1;
        if (horas > 168) horas = 168; // 7 dias max
        return ResponseEntity.ok(healthService.resumoGlobal(horas));
    }

    @GetMapping("/api/admin-internal/whatsapp/{instanceName}/historico")
    public ResponseEntity<List<Map<String, Object>>> historicoAdmin(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        WhatsappInstance inst = instanceRepo.findByInstanceName(instanceName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var logs = healthService.historico(inst.getId(), 24);
        var out = logs.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("em", h.getEm().toString());
            m.put("estado", h.getEstado().name());
            m.put("minutosSemMensagem", h.getMinutosSemMensagem());
            m.put("reconexaoDisparada", h.getReconexaoDisparada());
            return m;
        }).toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/admin-internal/whatsapp/{instanceName}/reconectar")
    public ResponseEntity<Map<String, Object>> reconectarAdmin(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        WhatsappInstance inst = instanceRepo.findByInstanceName(instanceName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean ok = healthService.tentarReconectar(inst);
        return ResponseEntity.ok(Map.of("ok", ok, "estadoAtual", healthService.avaliarEstado(inst).name()));
    }

    /**
     * RESET COMPLETO da instância — logout + delete na Evolution + apaga local.
     * Diferente do reconectar() que só refresca o socket Baileys: este destrava
     * sessão "shadow-banned" do número (WhatsApp marcou o número como suspeito
     * e silenciou as mensagens entrantes mantendo a sessão CONECTADA).
     *
     * Após reset, o restaurante precisa escanear NOVO QR pelo painel dele.
     * Use só quando reconectar() já tentou várias vezes e bot continua mudo.
     */
    /**
     * Histórico completo de eventos de conexão dessa instância
     * (QUEDA, RECONEXAO_TENTADA, RECONEXAO_OK, HEARTBEAT_FALHOU, etc).
     *
     * Devolve as últimas 50 linhas do whatsapp_desconexao_log.
     * Usado no painel admin pra ver "essa instância caiu 4x essa semana"
     * e no card do dono ("última queda foi X há Y horas").
     */
    @GetMapping("/api/admin-internal/whatsapp/{instanceName}/desconexoes")
    public ResponseEntity<Map<String, Object>> desconexoesAdmin(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        WhatsappInstance inst = instanceRepo.findByInstanceName(instanceName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var eventos = desconexaoLogRepo.findByInstanceIdOrderByCriadoEmDesc(
                inst.getId(), org.springframework.data.domain.PageRequest.of(0, 50));
        var lista = eventos.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("em", e.getCriadoEm() == null ? null : e.getCriadoEm().toString());
            m.put("tipo", e.getTipo().name());
            m.put("motivo", e.getMotivo());
            m.put("codigoApi", e.getCodigoApi());
            m.put("statusAntes", e.getStatusAntes());
            m.put("statusDepois", e.getStatusDepois());
            m.put("duracaoMin", e.getDuracaoMin());
            m.put("msgsCiclo", e.getMsgsProcessadasNoCiclo());
            m.put("tentativaNum", e.getTentativaNum());
            m.put("correlationId", e.getCorrelationId());
            return m;
        }).toList();
        // Sumário: contagem por tipo nas últimas 24h
        var contagem = desconexaoLogRepo.contarPorTipoDesde(
                java.time.LocalDateTime.now().minusHours(24));
        Map<String, Long> summary24h = new LinkedHashMap<>();
        for (Object[] row : contagem) {
            summary24h.put(row[0].toString(), ((Number) row[1]).longValue());
        }
        return ResponseEntity.ok(Map.of(
                "instanceName", instanceName,
                "eventos", lista,
                "summary24h", summary24h,
                "estadoAtual", Map.of(
                        "status", inst.getStatus().name(),
                        "sessaoIniciadaEm", inst.getSessaoIniciadaEm() == null ? null
                                : inst.getSessaoIniciadaEm().toString(),
                        "conectadoEm", inst.getConectadoEm() == null ? null
                                : inst.getConectadoEm().toString(),
                        "ultimoHeartbeatEm", inst.getUltimoHeartbeatEm() == null ? null
                                : inst.getUltimoHeartbeatEm().toString(),
                        "ultimoHeartbeatOk", inst.getUltimoHeartbeatOk(),
                        "heartbeatsFalhadosSeguidos", inst.getHeartbeatsFalhadosSeguidos(),
                        "warmupForcadoAte", inst.getWarmupForcadoAte() == null ? null
                                : inst.getWarmupForcadoAte().toString(),
                        "msgsCicloAtual", inst.getMsgsCicloAtual()
                )
        ));
    }

    /**
     * Marca a instância como "veterana" — kill-switch pro guard WARMUP.
     * Uso: dono migrou número usado há meses em outro sistema. Não faz
     * sentido tratar como conta nova de 48h.
     *
     * Efeito: seta warmup_forcado_ate = agora (data no passado → força off).
     */
    @PostMapping("/api/admin-internal/whatsapp/{instanceName}/marcar-veterana")
    public ResponseEntity<Map<String, Object>> marcarVeterana(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        WhatsappInstance inst = instanceRepo.findByInstanceName(instanceName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        inst.setWarmupForcadoAte(java.time.LocalDateTime.now().minusMinutes(1));
        // Também retroativa sessaoIniciadaEm pra 3 dias atrás pra outros guards
        // que olham warmup independentemente do warmupForcadoAte.
        if (inst.getSessaoIniciadaEm() == null
                || inst.getSessaoIniciadaEm().isAfter(java.time.LocalDateTime.now().minusDays(3))) {
            inst.setSessaoIniciadaEm(java.time.LocalDateTime.now().minusDays(3));
        }
        instanceRepo.save(inst);
        whatsappService.registrarEventoAuditoria(inst,
                com.mydelivery.model.WhatsappDesconexaoLog.Tipo.ACAO_ADMIN,
                "marcada como veterana (warmup desligado)", null,
                inst.getStatus().name(), inst.getStatus().name(), null,
                "wa-admin-veterana-" + System.currentTimeMillis());
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "instanceName", instanceName,
                "sessaoIniciadaEm", inst.getSessaoIniciadaEm().toString(),
                "warmupForcadoAte", inst.getWarmupForcadoAte().toString()
        ));
    }

    @PostMapping("/api/admin-internal/whatsapp/{instanceName}/reset-full")
    public ResponseEntity<Map<String, Object>> resetFullAdmin(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        WhatsappInstance inst = instanceRepo.findByInstanceName(instanceName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Restaurante r = inst.getRestaurante();
        whatsappService.resetar(r);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "instanceName", instanceName,
                "mensagem", "Reset completo executado. Restaurante precisa escanear QR novo."
        ));
    }

    /**
     * Diagnóstico completo: por que esse bot está calado AGORA?
     *
     * Devolve em JSON um veredito direto + checagens detalhadas:
     *  - botAtivo: bot ligado/desligado
     *  - status: CONECTADA / DESCONECTADA / AGUARDANDO_QR / ERRO
     *  - heartbeats: minutos desde último evento Evolution + última msg cliente
     *  - webhook na Evolution: URL configurada vs URL esperada
     *  - últimos eventos no ring buffer (Evolution está MANDANDO algo?)
     *
     * Usado quando dono reclama "bot parou" — admin abre esse endpoint e em
     * 1 olhada vê o motivo. Sem precisar abrir log do Railway.
     */
    @GetMapping("/api/admin-internal/whatsapp/{instanceName}/diagnostico-bot")
    public ResponseEntity<Map<String, Object>> diagnosticoBot(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        WhatsappInstance inst = instanceRepo.findByInstanceName(instanceName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceName", instanceName);
        out.put("restauranteId", inst.getRestaurante() == null ? null : inst.getRestaurante().getId());
        out.put("restauranteNome", inst.getRestaurante() == null ? null : inst.getRestaurante().getNome());

        // ── Checagens ──
        boolean botAtivo = Boolean.TRUE.equals(inst.getBotAtivo());
        boolean conectada = inst.getStatus() == WhatsappInstance.Status.CONECTADA;
        Integer minSemEvento = healthService.minutosSemEventoEvolution(inst);
        Integer minSemCliente = healthService.minutosSemMensagemCliente(inst);

        out.put("botAtivo", botAtivo);
        out.put("status", inst.getStatus().name());
        out.put("estadoHealth", healthService.avaliarEstado(inst).name());
        out.put("minutosSemEventoEvolution", minSemEvento);
        out.put("minutosSemMensagemCliente", minSemCliente);

        // ── Webhook Evolution (URL salva lá vs esperada) ──
        String urlEsperada = "/api/webhooks/whatsapp/" + instanceName;
        try {
            Map<String, Object> wh = whatsappService.diagWebhook(inst.getRestaurante());
            String urlAtual = wh == null ? null : String.valueOf(wh.get("url"));
            out.put("webhookEvolutionUrl", urlAtual);
            out.put("webhookConfigurado", urlAtual != null && urlAtual.endsWith(urlEsperada));
        } catch (Exception e) {
            out.put("webhookEvolutionUrl", "ERRO: " + e.getMessage());
            out.put("webhookConfigurado", false);
        }

        // ── Últimos eventos webhook (Evolution tá mandando algo?) ──
        var eventos = WhatsappWebhookController.snapshotEventos(instanceName);
        out.put("eventosRecentes", eventos.size());
        if (!eventos.isEmpty()) {
            out.put("ultimoEvento", eventos.get(0));
        }

        // ── VEREDITO ──
        List<String> problemas = new java.util.ArrayList<>();
        if (!botAtivo) problemas.add("Bot está DESLIGADO (botAtivo=false). Ligar no painel do restaurante.");
        if (!conectada) problemas.add("Status NÃO é CONECTADA: " + inst.getStatus()
                + ". Precisa de QR novo ou reset full.");
        if (minSemEvento != null && minSemEvento > 60)
            problemas.add("Evolution não manda evento há " + minSemEvento
                    + " min — sessão ZUMBI. Forçar reconexão.");
        if (eventos.isEmpty())
            problemas.add("Zero eventos no buffer. Evolution nunca falou com este backend OU webhook URL errada.");
        if (Boolean.FALSE.equals(out.get("webhookConfigurado")))
            problemas.add("Webhook configurado na Evolution NÃO bate com a URL esperada — atualizar via /resetWebhook.");

        if (problemas.isEmpty()) {
            out.put("veredito", "✅ Bot deveria estar respondendo. Veja logs [Bot:SILENCIO] ou [WhatsApp:SILENCIO] no Railway pra detalhe da última mensagem.");
        } else {
            out.put("veredito", "❌ Problemas detectados: " + String.join(" | ", problemas));
        }
        out.put("problemas", problemas);

        return ResponseEntity.ok(out);
    }

    /**
     * Últimos webhooks recebidos da Evolution pra essa instância (ring buffer
     * in-memory). Permite ao admin investigar "por que esse bot está dormindo"
     * sem precisar acessar Railway logs.
     */
    @GetMapping("/api/admin-internal/whatsapp/{instanceName}/eventos")
    public ResponseEntity<Map<String, Object>> eventosAdmin(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        validarSecret(secret);
        var eventos = WhatsappWebhookController.snapshotEventos(instanceName);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceName", instanceName);
        out.put("totalEventos", eventos.size());
        out.put("eventos", eventos);
        return ResponseEntity.ok(out);
    }

    private void validarSecret(String received) {
        if (adminSecret == null || adminSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "ADMIN_INTERNAL_SECRET não configurado");
        }
        if (!adminSecret.equals(received)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Segredo inválido");
        }
    }
}
