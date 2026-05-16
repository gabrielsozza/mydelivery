package com.mydelivery.service.whatsapp;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.config.EvolutionProperties;
import com.mydelivery.model.Restaurante;
import com.mydelivery.model.WhatsappInstance;
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
    private final EvolutionClient evolutionClient;
    private final EvolutionProperties props;

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
            inst = criarNova(restaurante);
        }

        // Refresca status atual da Evolution antes de decidir o que fazer
        atualizarStatusDaEvolution(inst);
        repo.save(inst);

        if (inst.getStatus() == WhatsappInstance.Status.CONECTADA) {
            return inst; // nada a fazer
        }

        // Busca/renova QR
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = evolutionClient.conectar(inst.getInstanceName());
            String qr = extrairQrCode(resp);
            if (qr != null) {
                // Evolution v1.x devolveu QR direto no response
                inst.setQrCode(qr);
                inst.setQrExpiraEm(LocalDateTime.now().plusSeconds(60));
            }
            // Em Evolution v2.1.x o /connect responde {"count":0} sem QR — ele chega
            // depois via webhook QRCODE_UPDATED. Marcamos AGUARDANDO_QR de qualquer
            // forma pra o frontend começar o polling em /status.
            inst.setStatus(WhatsappInstance.Status.AGUARDANDO_QR);
        } catch (RuntimeException e) {
            log.error("[WhatsApp] Erro ao gerar QR pra {}: {}", inst.getInstanceName(), e.getMessage());
            inst.setStatus(WhatsappInstance.Status.ERRO);
        }

        return repo.save(inst);
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
        return repo.save(inst);
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
     */
    public void enviarMensagem(WhatsappInstance inst, String numeroDestino, String texto) {
        if (inst.getStatus() != WhatsappInstance.Status.CONECTADA) {
            log.warn("[WhatsApp] Pulando envio: instância {} não está conectada", inst.getInstanceName());
            return;
        }
        try {
            evolutionClient.enviarTexto(inst.getInstanceName(), inst.getInstanceToken(), numeroDestino, texto);
            log.info("[WhatsApp] Mensagem enviada — instância={}, para={}***", inst.getInstanceName(),
                    numeroDestino.length() > 5 ? numeroDestino.substring(0, 5) : numeroDestino);
        } catch (RuntimeException e) {
            log.error("[WhatsApp] Falha ao enviar: {}", e.getMessage());
        }
    }

    // ── webhook helpers ──

    /** Lookup por nome de instância (usado pelo WebhookController). */
    public WhatsappInstance buscarPorNome(String instanceName) {
        return repo.findByInstanceName(instanceName).orElse(null);
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
        String nome = nomeInstancia(restaurante);
        String webhookUrl = props.getWebhookBaseUrl() + "/api/webhooks/whatsapp/" + nome;

        log.info("[WhatsApp] Criando instância {} (webhook={})", nome, webhookUrl);

        Map<String, Object> resp;
        try {
            resp = evolutionClient.criarInstancia(nome, webhookUrl);
        } catch (RuntimeException e) {
            // Pode ser que a instância já exista na Evolution (caso DB local foi resetado).
            // Recupera o estado dela em vez de falhar.
            log.warn("[WhatsApp] criarInstancia falhou ({}). Tentando reusar via /connect.", e.getMessage());
            resp = Map.of();
        }

        String token = extrairInstanceToken(resp);

        return repo.save(WhatsappInstance.builder()
                .restaurante(restaurante)
                .instanceName(nome)
                .instanceToken(token)
                .status(WhatsappInstance.Status.NOVA)
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
                if (inst.getStatus() != WhatsappInstance.Status.CONECTADA) {
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
