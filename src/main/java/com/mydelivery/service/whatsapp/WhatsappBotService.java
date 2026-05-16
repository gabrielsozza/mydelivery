package com.mydelivery.service.whatsapp;

import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.mydelivery.config.EvolutionProperties;
import com.mydelivery.model.ConfiguracaoRestaurante;
import com.mydelivery.model.Restaurante;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bot de atendimento — leve, reativo, anti-spam.
 *
 * Princípios:
 *  - Só responde a quem mandar mensagem primeiro.
 *  - 1 resposta automática por número a cada N segundos (throttle).
 *  - Cliente pede "atendente" → bot fica silente por N minutos (modo humano).
 *  - Nenhum disparo em massa, nenhuma sequência automática, nenhum follow-up.
 *  - Regras simples por palavra-chave; sem chamada de LLM nesta versão.
 *
 * Estado in-memory por número:
 *  - ultimaResposta: pra throttle.
 *  - silencioAte: pra modo atendente humano.
 * Reset no restart é OK — bot retoma comportamento normal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsappBotService {

    private final WhatsappService whatsappService;
    private final ConfiguracaoRestauranteRepository configRepo;
    private final EvolutionProperties props;

    /** Estado por número (key = instanceName + ":" + numero). */
    private final Map<String, EstadoNumero> estados = new ConcurrentHashMap<>();

    /**
     * Processa mensagem recebida. Se bot está ligado e regras casam, responde.
     * Caso contrário, fica em silêncio.
     *
     * @param inst   instância do restaurante destinatário
     * @param numero remetente (formato Evolution: 5511XXX@s.whatsapp.net ou só dígitos)
     * @param texto  mensagem recebida (texto puro; mídias são ignoradas)
     */
    public void processar(WhatsappInstance inst, String numero, String texto) {
        if (!Boolean.TRUE.equals(inst.getBotAtivo())) {
            log.debug("[Bot] desligado pra instância {} — não responde", inst.getInstanceName());
            return;
        }
        if (texto == null || texto.isBlank()) return;

        String numeroLimpo = limparNumero(numero);
        String chave = inst.getInstanceName() + ":" + numeroLimpo;
        EstadoNumero st = estados.computeIfAbsent(chave, k -> new EstadoNumero());

        LocalDateTime agora = LocalDateTime.now();

        // Modo silêncio: cliente pediu atendente humano → bot fica quieto
        if (st.silencioAte != null && agora.isBefore(st.silencioAte)) {
            log.debug("[Bot] em silêncio pra {} (até {})", chave, st.silencioAte);
            return;
        }

        // Throttle: evita flood se cliente mandar várias mensagens seguidas
        if (st.ultimaResposta != null
                && Duration.between(st.ultimaResposta, agora).toSeconds() < props.getBot().getThrottleSegundos()) {
            log.debug("[Bot] throttle ativo pra {}", chave);
            return;
        }

        String resposta = decidirResposta(inst.getRestaurante(), texto, st);
        if (resposta == null) {
            // Sem regra casando: não responde (evita ruído).
            return;
        }

        whatsappService.enviarMensagem(inst, numeroLimpo, resposta);
        st.ultimaResposta = agora;
        st.saudou = true;

        // Se a regra ativou modo humano, marca silêncio
        if (st.pediuAtendente) {
            st.silencioAte = agora.plusMinutes(props.getBot().getSilencioMinutos());
            st.pediuAtendente = false;
        }
    }

    // ── regras ──

    /** Retorna texto da resposta ou null pra ignorar a mensagem. */
    private String decidirResposta(Restaurante restaurante, String texto, EstadoNumero st) {
        String t = normalizar(texto);
        ConfiguracaoRestaurante cfg = configRepo.findByRestauranteId(restaurante.getId()).orElse(null);

        // 1. Atendente humano (prioridade máxima)
        if (contemAlguma(t, "atendente", "humano", "pessoa", "falar com alguem", "falar com voce")) {
            st.pediuAtendente = true;
            return "Sem problema! Em instantes um atendente da " + restaurante.getNome()
                    + " vai continuar com você por aqui. 😊";
        }

        // 2. Cardápio / menu
        if (contemAlguma(t, "cardapio", "menu", "ver cardapio", "produtos", "comprar")) {
            String link = montarLinkCardapio(restaurante);
            return "Aqui está nosso cardápio 👉 " + link + "\n\nÉ só escolher os itens e fazer o pedido pelo site!";
        }

        // 3. Horário
        if (contemAlguma(t, "horario", "que horas", "abre", "aberto", "funcionamento")) {
            return montarRespostaHorario(restaurante);
        }

        // 4. Taxa de entrega / pedido mínimo
        if (contemAlguma(t, "taxa", "entrega", "frete", "minimo")) {
            return montarRespostaEntrega(restaurante, cfg);
        }

        // 5. Saudação inicial (só na primeira interação do número)
        if (!st.saudou && contemAlguma(t, "oi", "ola", "bom dia", "boa tarde", "boa noite", "hey")) {
            String link = montarLinkCardapio(restaurante);
            return "Olá! 👋 Aqui é da " + restaurante.getNome() + ".\n\n"
                    + "Você pode fazer seu pedido pelo nosso cardápio online: " + link + "\n\n"
                    + "Posso te ajudar com algo? (digite *cardápio*, *horário*, *taxa* ou *atendente*)";
        }

        return null; // sem regra → silêncio
    }

    private String montarLinkCardapio(Restaurante r) {
        // Link público do cardápio. Em prod, app.url + ?slug=.
        return "https://mydeliveryfood.com.br/" + r.getSlug();
    }

    private String montarRespostaHorario(Restaurante r) {
        if (Boolean.TRUE.equals(r.getAberto())) {
            return "Estamos *abertos* agora! 🟢 Faça seu pedido pelo cardápio.";
        }
        return "No momento estamos fechados. 🌙 Mas você pode dar uma olhada no cardápio: "
                + montarLinkCardapio(r);
    }

    private String montarRespostaEntrega(Restaurante r, ConfiguracaoRestaurante cfg) {
        StringBuilder sb = new StringBuilder();
        if (r.getTaxaEntrega() != null) {
            sb.append("🛵 Taxa de entrega: R$ ").append(r.getTaxaEntrega()).append("\n");
        }
        if (r.getPedidoMinimo() != null) {
            sb.append("💰 Pedido mínimo: R$ ").append(r.getPedidoMinimo()).append("\n");
        }
        if (r.getTempoEntrega() != null) {
            sb.append("⏱️ Tempo médio: ").append(r.getTempoEntrega()).append(" minutos\n");
        }
        if (sb.length() == 0) {
            return "As taxas variam por região — confira no nosso cardápio: " + montarLinkCardapio(r);
        }
        sb.append("\nFaça seu pedido pelo cardápio: ").append(montarLinkCardapio(r));
        return sb.toString();
    }

    // ── helpers ──

    private boolean contemAlguma(String texto, String... palavras) {
        for (String p : palavras) if (texto.contains(p)) return true;
        return false;
    }

    /** Normaliza: lowercase + remove acentos. */
    private String normalizar(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return n.toLowerCase();
    }

    /** Tira sufixo @s.whatsapp.net e qualquer não-dígito. */
    private String limparNumero(String numero) {
        if (numero == null) return "";
        int at = numero.indexOf('@');
        String n = at >= 0 ? numero.substring(0, at) : numero;
        return n.replaceAll("\\D", "");
    }

    private static class EstadoNumero {
        LocalDateTime ultimaResposta;
        LocalDateTime silencioAte;
        boolean saudou;
        boolean pediuAtendente;
    }
}
