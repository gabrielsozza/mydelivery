package com.mydelivery.service.whatsapp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.mydelivery.config.UazapiProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * HTTP client pra Uazapi API (WhatsApp) — substitui EvolutionClient.
 *
 * <p>Mantém <b>a mesma interface pública</b> do EvolutionClient de propósito:
 * quem chama ({@code WhatsappService}, {@code WhatsappWatchdogJob},
 * {@code WhatsappReconnectJob}, etc.) continua com a mesma assinatura, e as
 * respostas são <b>traduzidas</b> pro formato Evolution ({@code hash.apikey},
 * {@code base64}, {@code instance.state = "open|close"}). Isso mantém as ~2000
 * linhas de {@code WhatsappService} intactas — só troca a injeção de tipo.
 *
 * <h3>Diferenças de auth vs Evolution</h3>
 * <ul>
 *   <li>Evolution usava um único header {@code apikey} pra tudo (com apikey
 *       global vs. da instância).</li>
 *   <li>Uazapi tem 2 headers distintos:
 *     <ul>
 *       <li>{@code admintoken} — cria/lista instâncias (uso admin).</li>
 *       <li>{@code token} — opera 1 instância (retornado no create).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>Webhook global</h3>
 * O webhook do Uazapi é <b>global do servidor</b>, configurado no painel deles
 * (URL, eventos, filtros). Não é por instância. Por isso, {@link #definirWebhook}
 * e {@link #consultarWebhook} são no-ops: só existem pra manter contrato com o
 * EvolutionClient. O dono já configurou o webhook global pra:
 * {@code https://api.mydeliveryfood.com.br/api/webhooks/whatsapp/uazapi}.
 */
@Slf4j
@Component
public class UazapiClient {

    private final RestClient restClient;
    private final UazapiProperties props;

    public UazapiClient(UazapiProperties props) {
        this.props = props;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(props.getTimeoutMs()).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(props.getTimeoutMs()).toMillis());

        // baseUrl pode vir vazio em dev — permite subir sem crashar.
        // As chamadas vão falhar no runtime com msg clara, sem quebrar boot.
        String base = props.getBaseUrl() == null ? "" : props.getBaseUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        this.restClient = RestClient.builder()
                .baseUrl(base.isEmpty() ? "http://localhost:8081" : base)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("[Uazapi] boot: baseUrl={} adminToken={}chars",
                base.isEmpty() ? "(vazio)" : base,
                props.getAdminToken() == null ? 0 : props.getAdminToken().length());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INSTÂNCIA
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Cria instância na Uazapi. {@code webhookUrl} é IGNORADO (webhook Uazapi é
     * global do servidor, configurado no painel — não vai por instância).
     *
     * <p>Retorna Map <b>no formato Evolution</b>:
     * <pre>
     * {
     *   "instance": { "instanceId": "&lt;id_uazapi&gt;", "instanceName": "&lt;name&gt;" },
     *   "hash": { "apikey": "&lt;instance_token&gt;" }
     * }
     * </pre>
     * Isso mantém {@code WhatsappService.extrairInstanceToken(resp)} funcionando.
     */
    public Map<String, Object> criarInstancia(String instanceName, String webhookUrl) {
        return criarInstancia(instanceName, webhookUrl, null);
    }

    /**
     * Overload com {@code proxyPool} — parâmetro IGNORADO (Uazapi cuida do proxy
     * residencial internamente na infra deles). Mantido pra compat com callers.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> criarInstancia(String instanceName, String webhookUrl, String proxyPool) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", instanceName);
        Map<String, Object> resp;
        try {
            resp = executar("POST", "/instance/create", adminToken(), body, Map.class);
        } catch (RuntimeException e) {
            // Conflito comum: nome já em uso no Uazapi (loja tentou reconectar
            // sem que a instância antiga tivesse sido apagada). Em vez de gastar
            // outro slot com nome diferente, RECUPERA a existente via /instance/all
            // e retorna o token dela — assim reusamos a mesma instância.
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("exist") || msg.contains("use") || msg.contains("dupl")
                    || msg.contains("conflict") || msg.contains("409") || msg.contains("400")) {
                log.info("[Uazapi][create] {} já existe no Uazapi — recuperando existente via /instance/all",
                        instanceName);
                var existente = recuperarInstanciaExistente(instanceName);
                if (existente != null) return existente;
            }
            throw e;
        }
        // Log diagnóstico do body cru — essencial pra diagnosticar formato divergente
        // ("token" vs "hash" vs aninhado). Só as chaves top-level pra não vazar secret.
        log.info("[Uazapi][create] instance={} resp-keys={} resp-size~={}",
                instanceName,
                resp == null ? "null" : resp.keySet(),
                resp == null ? 0 : resp.size());
        // Uazapi pode devolver em vários shapes — normalizamos todos:
        //   { "id": "...", "token": "...", "name": "..." }
        //   { "instance": { "id": "...", "token": "...", ... } }
        //   { "instance": { "id": "...", ... }, "token": "..." }
        String id = null;
        String token = null;
        if (resp != null) {
            Object rid = resp.get("id");
            Object rtk = resp.get("token");
            Object rinst = resp.get("instance");
            if (rid != null) id = rid.toString();
            if (rtk != null) token = rtk.toString();
            if (rinst instanceof Map<?, ?> im) {
                Map<String, Object> imMap = (Map<String, Object>) im;
                if (id == null && imMap.get("id") != null) id = imMap.get("id").toString();
                if (token == null && imMap.get("token") != null) token = imMap.get("token").toString();
            }
            // Uazapi tb pode devolver token em "apitoken" ou "apikey" (compat).
            if (token == null) {
                Object t2 = resp.get("apitoken");
                if (t2 != null) token = t2.toString();
            }
            if (token == null) {
                Object t3 = resp.get("apikey");
                if (t3 != null) token = t3.toString();
            }
        }
        if (token == null || token.isBlank()) {
            log.error("[Uazapi][create] SEM TOKEN no response — QR não vai ser gerado! resp={}", resp);
        } else {
            log.info("[Uazapi][create] token capturado ({}chars) pra instância={}", token.length(), instanceName);
        }
        return montarRespostaCriacao(instanceName, id, token);
    }

    /**
     * Gera QR Code pra pareamento. Retorna Map <b>no formato Evolution</b>
     * ({@code base64}) pra o {@code extrairQrCode} do WhatsappService achar.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> conectar(String instanceName) {
        String token = resolverTokenComFallback(instanceName);
        if (token == null) {
            log.error("[Uazapi] conectar({}) SEM TOKEN — cache vazio e DB tb sem registro. QR não vai vir.",
                    instanceName);
            return Collections.emptyMap();
        }
        // Body vazio = gera QR. Se passasse phone, seria pairing code.
        Map<String, Object> resp = executar("POST", "/instance/connect", token, new HashMap<>(), Map.class);
        log.info("[Uazapi][connect] instance={} resp-keys={} temQr={}",
                instanceName,
                resp == null ? "null" : resp.keySet(),
                resp != null && (resp.containsKey("qrcode") || resp.containsKey("qr") || resp.containsKey("base64")));
        // Extrai o número do WhatsApp se veio (instância já conectada) — salva
        // no cache pro WhatsappService consultar depois via getPhone().
        capturarPhoneDaResposta(instanceName, resp);
        return traduzirQrParaFormatoEvolution(resp);
    }

    /** Cache paralelo: nome da instância → número WhatsApp conectado (só dígitos). */
    private final java.util.concurrent.ConcurrentMap<String, String> phonesPorNome =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Extrai {@code jid.user} de qualquer response do Uazapi e guarda no cache.
     * Formato típico: {@code { jid: { user: "5511999...", server: "s.whatsapp.net" } }}.
     */
    @SuppressWarnings("unchecked")
    private void capturarPhoneDaResposta(String instanceName, Map<String, Object> resp) {
        if (resp == null || instanceName == null) return;
        Object jid = resp.get("jid");
        // Pode estar aninhado em instance.jid ou status.jid
        if (jid == null) {
            Object inst = resp.get("instance");
            if (inst instanceof Map<?, ?> im) jid = ((Map<String, Object>) im).get("jid");
        }
        if (jid == null) {
            Object st = resp.get("status");
            if (st instanceof Map<?, ?> sm) jid = ((Map<String, Object>) sm).get("jid");
        }
        if (jid instanceof Map<?, ?> jm) {
            Object user = ((Map<String, Object>) jm).get("user");
            if (user != null) {
                String phone = user.toString().replaceAll("[^0-9]", "");
                if (phone.length() >= 10) {
                    phonesPorNome.put(instanceName, phone);
                    log.info("[Uazapi] phone capturado pra {}: {}***",
                            instanceName,
                            phone.length() > 4 ? phone.substring(0, 4) : phone);
                }
            }
        }
    }

    /**
     * Retorna o número WhatsApp conectado da instância (só dígitos), ou {@code null}.
     * WhatsappService usa após {@code criarNova}/{@code conectar} pra salvar no
     * {@link com.mydelivery.model.WhatsappInstance#phone} — assim o webhook
     * subsequente que vem com o NÚMERO como identificador acha a linha certa.
     */
    public String getPhone(String instanceName) {
        return phonesPorNome.get(instanceName);
    }

    /**
     * Cache in-memory pode estar vazio (bootstrap ainda não rodou, ou boot
     * novo antes de bootstrapTokens). Fallback: busca no {@link
     * com.mydelivery.repository.WhatsappInstanceRepository}. Se achou, popula
     * o cache pra próximas chamadas serem O(1).
     */
    private String resolverTokenComFallback(String instanceName) {
        String cache = tokensPorNome.get(instanceName);
        if (cache != null && !cache.isBlank()) return cache;
        if (repoRef != null) {
            try {
                var opt = repoRef.findByInstanceName(instanceName);
                if (opt.isPresent()) {
                    String tk = opt.get().getInstanceToken();
                    if (tk != null && !tk.isBlank()) {
                        tokensPorNome.put(instanceName, tk);
                        log.info("[Uazapi] token de {} carregado via fallback do DB", instanceName);
                        return tk;
                    }
                }
            } catch (Exception e) {
                log.warn("[Uazapi] fallback DB falhou pra {}: {}", instanceName, e.getMessage());
            }
        }
        return null;
    }

    /** Referência pro repo — populada no {@link #bootstrapTokens}. */
    private volatile com.mydelivery.repository.WhatsappInstanceRepository repoRef;

    /**
     * Consulta estado da instância. Retorna Map no formato Evolution:
     * {@code { instance: { state: "open" | "close" | "connecting" } }}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> consultarStatus(String instanceName) {
        String token = tokenDaInstancia(instanceName);
        if (token == null) {
            return Map.of("instance", Map.of("state", "close"));
        }
        Map<String, Object> resp = executar("GET", "/instance/status", token, null, Map.class);
        capturarPhoneDaResposta(instanceName, resp);
        return traduzirStatusParaFormatoEvolution(resp);
    }

    /** Faz logout (Uazapi mantém a instância — pode reconectar). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> logout(String instanceName) {
        String token = tokenDaInstancia(instanceName);
        if (token == null) return Collections.emptyMap();
        return executar("POST", "/instance/disconnect", token, new HashMap<>(), Map.class);
    }

    /**
     * Reinicia a sessão. Uazapi não tem endpoint restart direto — emulamos com
     * disconnect + connect. Preserva pareamento (não precisa novo QR).
     */
    public Map<String, Object> restart(String instanceName) {
        try { logout(instanceName); } catch (RuntimeException ignore) {}
        return conectar(instanceName);
    }

    /** Deleta instância no Uazapi. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deletar(String instanceName) {
        String token = tokenDaInstancia(instanceName);
        if (token == null) return Collections.emptyMap();
        return executar("DELETE", "/instance", token, null, Map.class);
    }

    /**
     * Lista TODAS as instâncias do servidor Uazapi. Traduz cada item pro
     * formato Evolution (chave {@code name}) pra callers legados funcionarem.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchInstances() {
        try {
            Object resp = executar("GET", "/instance/all", adminToken(), null, Object.class);
            List<Map<String, Object>> out = new ArrayList<>();
            if (resp instanceof List<?> lst) {
                for (Object o : lst) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> mm = (Map<String, Object>) m;
                        Map<String, Object> item = new LinkedHashMap<>();
                        // WhatsappService espera field "instanceName" — traduz "name".
                        Object name = mm.get("name");
                        Object id = mm.get("id");
                        item.put("instanceName", name != null ? name.toString() : "");
                        item.put("instanceId", id != null ? id.toString() : "");
                        item.put("id", id != null ? id.toString() : "");
                        out.add(item);
                    }
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("[Uazapi] fetchInstances falhou: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MENSAGEM
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Envia mensagem de texto. {@code delayMs} vira {@code delay} no body do
     * {@code /send/text} da Uazapi — mostra "digitando…" pelo tempo indicado.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> enviarTexto(String instanceName, String instanceToken,
                                            String number, String text, int delayMs) {
        String token = tokenEfetivo(instanceName, instanceToken);
        Map<String, Object> body = new HashMap<>();
        body.put("number", number);
        body.put("text", text);
        // Linkpreview off — evita gerar snippet grande que aparece cheio de
        // metadata (dá "cara de bot" e ainda consome banda extra).
        body.put("linkPreview", false);
        if (delayMs > 0) body.put("delay", delayMs);
        return executar("POST", "/send/text", token, body, Map.class);
    }

    /** Overload sem delay — mantém compat. */
    public Map<String, Object> enviarTexto(String instanceName, String instanceToken,
                                            String number, String text) {
        return enviarTexto(instanceName, instanceToken, number, text, 0);
    }

    /**
     * Envia mídia (imagem por URL) com legenda. Uazapi usa {@code /send/media}
     * com {@code type=image}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> enviarMidia(String instanceName, String instanceToken,
                                            String number, String mediaUrl,
                                            String caption, int delayMs) {
        String token = tokenEfetivo(instanceName, instanceToken);
        Map<String, Object> body = new HashMap<>();
        body.put("number", number);
        body.put("type", "image");
        body.put("file", mediaUrl);
        if (caption != null && !caption.isEmpty()) body.put("text", caption);
        if (delayMs > 0) body.put("delay", delayMs);
        return executar("POST", "/send/media", token, body, Map.class);
    }

    /**
     * Marca msg como lida. Best-effort — silencia falhas (feature "nice to have").
     */
    @SuppressWarnings("unchecked")
    public void marcarComoLida(String instanceName, String instanceToken,
                                String remoteJid, String messageId, boolean fromMe) {
        try {
            String token = tokenEfetivo(instanceName, instanceToken);
            Map<String, Object> body = new HashMap<>();
            body.put("number", remoteJid);
            body.put("messageid", messageId);
            executar("POST", "/message/markread", token, body, Map.class);
        } catch (Exception ignored) {
            // Best-effort
        }
    }

    /**
     * Webhook do Uazapi é global do servidor — chamado retornar map vazio
     * pra callers legados que não sabem disso ainda. Mantido pra compat.
     */
    public Map<String, Object> consultarWebhook(String instanceName) {
        return Collections.emptyMap();
    }

    /**
     * No-op — webhook do Uazapi é configurado no painel deles pelo admin,
     * não por API por instância. Mantido pra callers legados não quebrarem.
     */
    public Map<String, Object> definirWebhook(String instanceName, String webhookUrl) {
        return Collections.emptyMap();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Store de instanceToken por nome (thread-safe)
    // Populado sempre que criarInstancia() ou enviarTexto(...) recebe token.
    // Serve pra endpoints que só sabem o nome (conectar, status, logout).
    // ═══════════════════════════════════════════════════════════════════════
    private final java.util.concurrent.ConcurrentMap<String, String> tokensPorNome =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Registra o token da instância. Chamado pelo WhatsappService após ele
     * extrair o token do body do {@code criarInstancia} — permite que chamadas
     * subsequentes ({@code conectar}, {@code consultarStatus}) usem esse token
     * sem ter que ficar carregando ele em memória do lado de fora.
     */
    public void registrarToken(String instanceName, String token) {
        if (instanceName != null && token != null && !token.isBlank()) {
            tokensPorNome.put(instanceName, token);
        }
    }

    /**
     * Bootstrap do cache de tokens no boot da aplicação: lê TODAS as
     * {@code WhatsappInstance} do banco e popula o mapa. Sem isso, após
     * restart do backend as chamadas ao {@code /instance/status},
     * {@code /instance/connect}, etc. falhariam porque só existe o token
     * em memória. Executa via {@code ApplicationReadyEvent} pra garantir
     * que o repositório está pronto.
     */
    /**
     * Setter injection opcional pro repositório. Usar {@code @Autowired} em
     * parâmetro de {@code @EventListener} não funciona (Spring passa o evento
     * como argumento, não resolve dependência) — por isso a injeção fica aqui.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWhatsappInstanceRepository(com.mydelivery.repository.WhatsappInstanceRepository repo) {
        this.repoRef = repo;
    }

    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void bootstrapTokens() {
        if (repoRef == null) {
            log.info("[Uazapi] bootstrapTokens: repo não injetado — pulando");
            return;
        }
        try {
            int carregados = 0;
            for (var inst : repoRef.findAll()) {
                if (inst.getInstanceName() != null && inst.getInstanceToken() != null
                        && !inst.getInstanceToken().isBlank()) {
                    tokensPorNome.put(inst.getInstanceName(), inst.getInstanceToken());
                    carregados++;
                }
            }
            log.info("[Uazapi] cache de tokens populado — {} instâncias carregadas do banco", carregados);
        } catch (Exception e) {
            log.warn("[Uazapi] bootstrapTokens falhou: {}", e.getMessage());
        }
    }

    private String tokenDaInstancia(String instanceName) {
        return tokensPorNome.get(instanceName);
    }

    /** Prefere token passado pelo caller (fonte da verdade = DB); se null, cache. */
    private String tokenEfetivo(String instanceName, String tokenPassado) {
        if (tokenPassado != null && !tokenPassado.isBlank()) {
            tokensPorNome.putIfAbsent(instanceName, tokenPassado);
            return tokenPassado;
        }
        String cache = tokensPorNome.get(instanceName);
        if (cache != null) return cache;
        // Sem token: fallback pra adminToken (algumas rotas aceitam) —
        // erro claro no log se falhar.
        log.warn("[Uazapi] {} chamado sem token da instância — fallback pro adminToken", instanceName);
        return adminToken();
    }

    private String adminToken() {
        return props.getAdminToken() == null ? "" : props.getAdminToken();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tradução Uazapi → Formato Evolution (pra WhatsappService não mudar)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Fallback quando /instance/create dá conflito: busca no /instance/all
     * (admin) por instância cujo {@code name} bate. Retorna resposta no
     * mesmo shape que {@link #montarRespostaCriacao} produz — o caller
     * (WhatsappService) não percebe diferença.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> recuperarInstanciaExistente(String instanceName) {
        try {
            Object resp = executar("GET", "/instance/all", adminToken(), null, Object.class);
            java.util.List<?> lista = null;
            if (resp instanceof java.util.List<?> l) lista = l;
            else if (resp instanceof Map<?, ?> m && ((Map<?, ?>) m).get("instances") instanceof java.util.List<?> l2) {
                lista = l2;
            }
            if (lista == null) return null;
            for (Object o : lista) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> item = (Map<String, Object>) m;
                Object nm = item.get("name");
                if (nm != null && instanceName.equals(nm.toString())) {
                    Object id = item.get("id");
                    Object tk = item.get("token");
                    if (tk == null) tk = item.get("apitoken");
                    log.info("[Uazapi] recuperei instância existente {} (id={} token={}chars)",
                            instanceName,
                            id != null ? id.toString() : "?",
                            tk != null ? tk.toString().length() : 0);
                    return montarRespostaCriacao(
                            instanceName,
                            id != null ? id.toString() : null,
                            tk != null ? tk.toString() : null);
                }
            }
            log.warn("[Uazapi] /instance/all não achou instância com name={}", instanceName);
            return null;
        } catch (Exception e) {
            log.warn("[Uazapi] recuperarInstanciaExistente({}) falhou: {}", instanceName, e.getMessage());
            return null;
        }
    }

    /** Emula {@code { instance: {instanceId, instanceName}, hash: {apikey} }}. */
    private Map<String, Object> montarRespostaCriacao(String name, String id, String token) {
        if (token != null && !token.isBlank()) {
            registrarToken(name, token);
        }
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("instanceId", id != null ? id : "");
        instance.put("instanceName", name);
        Map<String, Object> hash = new LinkedHashMap<>();
        hash.put("apikey", token != null ? token : "");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instance", instance);
        out.put("hash", hash);
        // apikey no root também — WhatsappService.extrairInstanceToken tem fallback.
        out.put("apikey", token != null ? token : "");
        return out;
    }

    /** Uazapi devolve QR em {@code qrcode} (base64 sem prefixo) — traduz. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> traduzirQrParaFormatoEvolution(Map<String, Object> uazapiResp) {
        if (uazapiResp == null) return Collections.emptyMap();
        Object qr = uazapiResp.get("qrcode");
        if (qr == null) qr = uazapiResp.get("qr");
        if (qr == null) qr = uazapiResp.get("base64");
        // Se veio dentro de instance.qrcode
        Object inst = uazapiResp.get("instance");
        if (qr == null && inst instanceof Map<?, ?> im) {
            Object q2 = ((Map<String, Object>) im).get("qrcode");
            if (q2 != null) qr = q2;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (qr != null) {
            String qrStr = qr.toString();
            // Se veio raw (sem "data:image/png;base64,"), o extrairQrCode aceita.
            out.put("base64", qrStr);
            out.put("code", qrStr);
        }
        return out;
    }

    /**
     * Uazapi: {@code { status: { connected, loggedIn }, instance: {...} }}.
     * Evolution: {@code { instance: { state: "open"|"close"|"connecting" } }}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> traduzirStatusParaFormatoEvolution(Map<String, Object> uazapiResp) {
        String state = "close";
        if (uazapiResp != null) {
            Object st = uazapiResp.get("status");
            if (st instanceof Map<?, ?> stm) {
                Map<String, Object> stMap = (Map<String, Object>) stm;
                boolean connected = Boolean.TRUE.equals(stMap.get("connected"));
                boolean loggedIn = Boolean.TRUE.equals(stMap.get("loggedIn"));
                if (connected && loggedIn) state = "open";
                else if (connected || loggedIn) state = "connecting";
                else state = "close";
            } else {
                // Fallback: campo "status" pode ser string ("connected", "disconnected").
                Object inst = uazapiResp.get("instance");
                if (inst instanceof Map<?, ?> im) {
                    Object s = ((Map<String, Object>) im).get("status");
                    if (s != null) {
                        String sStr = s.toString().toLowerCase();
                        if (sStr.contains("connect") && !sStr.contains("dis")) state = "open";
                        else if (sStr.contains("connecting") || sStr.contains("qr")) state = "connecting";
                    }
                }
            }
        }
        return Map.of("instance", Map.of("state", state));
    }

    public UazapiProperties props() { return props; }

    // ═══════════════════════════════════════════════════════════════════════
    // CIRCUIT BREAKER — mesma estratégia do EvolutionClient
    // ═══════════════════════════════════════════════════════════════════════
    private static final AtomicInteger falhasSeguidas = new AtomicInteger(0);
    private static final AtomicReference<Long> circuitAbertoAte = new AtomicReference<>(0L);
    private static final AtomicInteger cbBackoffCiclos = new AtomicInteger(0);
    private static final int CB_LIMITE_FALHAS = 5;
    private static final long CB_TEMPO_ABERTO_BASE_MS = 30_000L;

    public boolean circuitBreakerAberto() {
        return System.currentTimeMillis() < circuitAbertoAte.get();
    }

    private void registrarFalha(String method, String path) {
        int f = falhasSeguidas.incrementAndGet();
        if (f >= CB_LIMITE_FALHAS && !circuitBreakerAberto()) {
            int ciclo = cbBackoffCiclos.incrementAndGet();
            long tempoAberto = Math.min(CB_TEMPO_ABERTO_BASE_MS * (long) Math.pow(2, ciclo - 1), 300_000L);
            circuitAbertoAte.set(System.currentTimeMillis() + tempoAberto);
            log.error("[Uazapi:CB] ABRINDO circuit breaker por {}s ({} falhas seguidas). Último: {} {}",
                    tempoAberto / 1000, f, method, path);
        }
    }

    private void registrarSucesso() {
        int f = falhasSeguidas.getAndSet(0);
        if (f > 0) log.info("[Uazapi:CB] recuperado após {} falhas", f);
        cbBackoffCiclos.set(0);
    }

    @SuppressWarnings("unchecked")
    private <T> T executar(String method, String path, String token, Object body, Class<T> tipo) {
        if (circuitBreakerAberto()) {
            long faltamMs = circuitAbertoAte.get() - System.currentTimeMillis();
            log.warn("[Uazapi:CB] rejeitando {} {} — circuit aberto por mais {}s", method, path, faltamMs / 1000);
            throw new EvolutionClient.EvolutionCircuitOpenException("Uazapi temporariamente indisponível (circuit aberto)");
        }
        if (token == null || token.isBlank()) {
            throw new RuntimeException("Uazapi: token vazio pra " + method + " " + path);
        }
        try {
            // Uazapi aceita AMBOS os headers "token" e "admintoken" — o servidor
            // decide qual usar por endpoint. Enviamos apenas o correto pra cada
            // chamada (admintoken pra /instance/create e /instance/all;
            // token pro resto). Aqui o caller já escolheu.
            final String headerName = isAdminToken(path) ? "admintoken" : "token";
            final String tk = token;
            Consumer<HttpHeaders> headers = h -> h.add(headerName, tk);

            T result = (T) switch (method) {
                case "POST"   -> restClient.post().uri(path).headers(headers)
                        .body(body == null ? "" : body).retrieve().body(tipo);
                case "GET"    -> restClient.get().uri(path).headers(headers).retrieve().body(tipo);
                case "DELETE" -> restClient.delete().uri(path).headers(headers).retrieve().body(tipo);
                default -> throw new IllegalArgumentException("Método não suportado: " + method);
            };
            registrarSucesso();
            return result;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) registrarFalha(method, path);
            log.error("[Uazapi] {} {} falhou [{}]: {}", method, path, e.getStatusCode(),
                    truncar(e.getResponseBodyAsString()));
            throw new RuntimeException("Uazapi API: " + truncar(e.getResponseBodyAsString()));
        } catch (Exception e) {
            registrarFalha(method, path);
            log.error("[Uazapi] {} {} erro: {}", method, path, e.getMessage());
            throw new RuntimeException("Não foi possível contactar a Uazapi API");
        }
    }

    /**
     * Endpoints administrativos que aceitam APENAS {@code admintoken}.
     * Pela documentação Uazapi: {@code /instance/create} (create) e
     * {@code /instance/all} (list). Todos os outros usam header {@code token}.
     */
    private static boolean isAdminToken(String path) {
        return "/instance/create".equals(path) || "/instance/all".equals(path);
    }

    private static String truncar(String s) {
        if (s == null) return "";
        return s.length() > 250 ? s.substring(0, 250) + "..." : s;
    }
}
