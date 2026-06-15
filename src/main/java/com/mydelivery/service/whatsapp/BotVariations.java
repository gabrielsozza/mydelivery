package com.mydelivery.service.whatsapp;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pools de variações + sorteio aleatório pra deixar o bot menos detectável.
 *
 * Princípio: bot que responde sempre EXATAMENTE igual é fingerprint óbvio
 * pro WhatsApp. Pequenas variações por mensagem (saudação, emoji, pontuação)
 * quebram esse padrão sem mudar UX do cliente.
 *
 * REGRAS APLICADAS (decisão do operador):
 *  - Pool de variações de texto ✅
 *  - Tom varia por horário do dia ✅
 *  - Emojis rotativos ✅
 *  - Pontuação variável ✅
 *  - Erros ortográficos ❌ (decisão: UX impecável)
 *  - "Ignorar primeira msg" ❌ (decisão: nunca deixar cliente sem resposta)
 */
public final class BotVariations {

    private BotVariations() {}

    /** Período do dia — usado pra mudar tom de saudação/emoji. */
    public enum Periodo {
        MADRUGADA, MANHA, TARDE, NOITE;
        public static Periodo agora() {
            int h = LocalTime.now().getHour();
            if (h < 6) return MADRUGADA;
            if (h < 12) return MANHA;
            if (h < 18) return TARDE;
            return NOITE;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SAUDAÇÕES — variam por período + variação aleatória
    // ═══════════════════════════════════════════════════════════════

    private static final List<String> SAUDA_MADRUGADA = List.of(
            "Oi", "Olá", "Boa madrugada", "Oi, tudo bem"
    );
    private static final List<String> SAUDA_MANHA = List.of(
            "Bom dia", "Oi, bom dia", "Olá, bom dia", "Bom diaa", "Oii, bom dia"
    );
    private static final List<String> SAUDA_TARDE = List.of(
            "Boa tarde", "Oi, boa tarde", "Olá, boa tarde", "Oii, boa tarde", "Eai, boa tarde"
    );
    private static final List<String> SAUDA_NOITE = List.of(
            "Boa noite", "Oi, boa noite", "Olá, boa noite", "Oii, boa noite", "Eai, boa noite"
    );

    /** Saudação aleatória contextualizada por horário. */
    public static String saudacao() {
        return switch (Periodo.agora()) {
            case MADRUGADA -> pick(SAUDA_MADRUGADA);
            case MANHA -> pick(SAUDA_MANHA);
            case TARDE -> pick(SAUDA_TARDE);
            case NOITE -> pick(SAUDA_NOITE);
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  EMOJIS rotativos por categoria
    // ═══════════════════════════════════════════════════════════════

    private static final List<String> EMOJI_CUMPRIMENTO = List.of("👋", "😊", "🙌", "🤝", "✋");
    private static final List<String> EMOJI_OK = List.of("✅", "👍", "✔️", "🆗", "😉");
    private static final List<String> EMOJI_COMIDA = List.of("🍔", "🍕", "🍽️", "🥗", "🍴");
    private static final List<String> EMOJI_MOTO = List.of("🛵", "🏍️", "🛴", "🚴");
    private static final List<String> EMOJI_TEMPO = List.of("⏱️", "⏰", "🕐", "⌛");
    private static final List<String> EMOJI_DINHEIRO = List.of("💳", "💰", "💵", "💸");
    private static final List<String> EMOJI_LOCAL = List.of("📍", "🗺️", "📌");
    private static final List<String> EMOJI_TELEFONE = List.of("📞", "☎️", "📲");
    private static final List<String> EMOJI_OBRIGADO = List.of("😊", "🙌", "💛", "✨", "😄");

    public static String emojiCumprimento() { return pick(EMOJI_CUMPRIMENTO); }
    public static String emojiOk() { return pick(EMOJI_OK); }
    public static String emojiComida() { return pick(EMOJI_COMIDA); }
    public static String emojiMoto() { return pick(EMOJI_MOTO); }
    public static String emojiTempo() { return pick(EMOJI_TEMPO); }
    public static String emojiDinheiro() { return pick(EMOJI_DINHEIRO); }
    public static String emojiLocal() { return pick(EMOJI_LOCAL); }
    public static String emojiTelefone() { return pick(EMOJI_TELEFONE); }
    public static String emojiObrigado() { return pick(EMOJI_OBRIGADO); }

    // ═══════════════════════════════════════════════════════════════
    //  PONTUAÇÃO — fim de frase variável
    //  Em vez de sempre terminar com ponto, varia entre {., !, ...}
    //  Probabilidade ponderada: . = comum (40%), ! = 30%, ... = 15%,
    //  vazio = 15% (também humano).
    // ═══════════════════════════════════════════════════════════════

    public static String pontuacaoFim() {
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < 0.40) return ".";
        if (r < 0.70) return "!";
        if (r < 0.85) return "...";
        return ""; // sem pontuação — humano de chat informal
    }

    // ═══════════════════════════════════════════════════════════════
    //  VARIAÇÕES DE EXPRESSÕES — pra trocar palavras-chave repetitivas
    // ═══════════════════════════════════════════════════════════════

    private static final List<String> VAR_CARDAPIO_AQUI = List.of(
            "Aqui está nosso cardápio",
            "Olha o cardápio aqui",
            "Segue o cardápio",
            "Tá aqui o cardápio",
            "Nosso cardápio"
    );
    public static String cardapioAqui() { return pick(VAR_CARDAPIO_AQUI); }

    private static final List<String> VAR_QUALQUER_DUVIDA = List.of(
            "Qualquer dúvida é só chamar",
            "Se precisar de algo, só me chamar",
            "Qualquer coisa, é só falar",
            "Tô por aqui pra qualquer dúvida",
            "Se tiver mais alguma pergunta, manda aí"
    );
    public static String qualquerDuvida() { return pick(VAR_QUALQUER_DUVIDA); }

    private static final List<String> VAR_POR_NADA = List.of(
            "Por nada",
            "Disponha",
            "Imagina",
            "De nada",
            "Tranquilo"
    );
    public static String porNada() { return pick(VAR_POR_NADA); }

    private static final List<String> VAR_COMBINADO = List.of(
            "Combinado",
            "Beleza",
            "Show",
            "Fechou",
            "Tá ótimo"
    );
    public static String combinado() { return pick(VAR_COMBINADO); }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    public static <T> T pick(List<T> opts) {
        if (opts == null || opts.isEmpty()) return null;
        return opts.get(ThreadLocalRandom.current().nextInt(opts.size()));
    }

    /** Delay aleatório pra marcar mensagem como lida — 800-3000ms. Simula
     *  humano que demora pra abrir conversa. */
    public static int randomReadDelayMs() {
        return ThreadLocalRandom.current().nextInt(800, 3001);
    }

    // ═══════════════════════════════════════════════════════════════
    //  NOTIFICAÇÃO PROATIVA — link de acompanhamento após pedido criado
    //
    //  Anti-shadowban: pool BEM grande de variações + composição
    //  aleatória (saudação + corpo + link + fechamento) = milhares de
    //  combinações possíveis, virtualmente impossível ter 2 mensagens
    //  idênticas em sequência mesmo com volume alto.
    // ═══════════════════════════════════════════════════════════════

    /** Aberturas curtas — variam por horário implícito. */
    private static final List<String> ACOMP_ABERTURA = List.of(
            "Oi!", "Eai!", "Olá!", "Opa!", "Oi, tudo bem?", "Eai, beleza?",
            "Oi tudo certo?", "Oi 😊", "Eai 👋", "Olá 🙌",
            "Oi! Pedido recebido por aqui", "Eai! Já anotamos seu pedido",
            "Opa, boa! Seu pedido tá aqui com a gente", "Oi, recebemos seu pedido"
    );

    /** Corpo principal — confirma recebimento. */
    private static final List<String> ACOMP_CORPO = List.of(
            "Seu pedido #%ID% chegou aqui com a gente",
            "Pedido #%ID% recebido com sucesso",
            "Anotei seu pedido #%ID%",
            "Pedido #%ID% tá em nossas mãos agora",
            "Recebemos seu pedido #%ID% e já começamos a separar",
            "Pedido #%ID% confirmado por aqui",
            "Tá confirmado seu pedido #%ID%",
            "Pedido #%ID% caiu direitinho aqui",
            "Pedido número %ID% recebido",
            "Pedido #%ID% chegou — começamos a preparar"
    );

    /** Convite pro link de acompanhamento — varia formato. */
    private static final List<String> ACOMP_CONVITE_LINK = List.of(
            "Você pode acompanhar em tempo real por aqui",
            "Pra acompanhar tudo em tempo real",
            "Quer acompanhar o status? Vai aqui",
            "Acompanha o andamento por esse link",
            "Pode acompanhar tudinho aqui",
            "Pra ver o status atualizadinho",
            "Acompanhamento em tempo real",
            "Aqui tu vê tudo atualizadinho",
            "Confere o status direto por aqui",
            "Dá pra acompanhar tudo aqui"
    );

    /** Fechamentos — variam tom e emoji. */
    private static final List<String> ACOMP_FECHAMENTO = List.of(
            "Qualquer coisa é só chamar",
            "Se precisar, é só me chamar",
            "Qualquer dúvida tô por aqui",
            "Qualquer coisa manda aí",
            "Se tiver dúvida é só chamar",
            "Tô por aqui se precisar de algo",
            "Qualquer coisa é só falar",
            "Se precisar, tamo junto",
            "Estou à disposição",
            "Se precisar de algo manda aí"
    );

    /** Emojis fim — opcionais (50% das vezes não usa). */
    private static final List<String> ACOMP_EMOJI_FIM = List.of(
            "", "", "", "", "", // 50% sem emoji
            "😊", "🙌", "👍", "✨", "🍴"
    );

    /**
     * Monta mensagem completa do tipo:
     *   "Oi! Anotei seu pedido #1247.
     *
     *    Acompanha o andamento por esse link 👉 {link}
     *
     *    Qualquer coisa é só chamar 😊"
     *
     * Combinação aleatória de 4 pools × 10-15 opções cada = milhares
     * de combinações. Praticamente impossível ter 2 mensagens idênticas
     * em sequência mesmo com 100 pedidos seguidos.
     */
    public static String montarMensagemAcompanhamento(Long pedidoId, String linkAcompanhar) {
        String abertura = pick(ACOMP_ABERTURA);
        String corpo = pick(ACOMP_CORPO).replace("%ID%", String.valueOf(pedidoId));
        String convite = pick(ACOMP_CONVITE_LINK);
        String fechamento = pick(ACOMP_FECHAMENTO);
        String emojiFim = pick(ACOMP_EMOJI_FIM);

        // Variação extra: 30% das vezes inverte ordem (link antes do fechamento curto)
        StringBuilder sb = new StringBuilder();
        sb.append(abertura).append(" ").append(corpo).append(pontuacaoFim()).append("\n\n");
        sb.append(convite).append(" 👉 ").append(linkAcompanhar).append("\n\n");
        sb.append(fechamento);
        if (emojiFim != null && !emojiFim.isEmpty()) {
            sb.append(" ").append(emojiFim);
        }
        sb.append(pontuacaoFim().isEmpty() ? "" : "");
        return sb.toString().trim();
    }

    /** Delay aleatório antes de mandar notificação proativa.
     *  15-90s — simula "humano que viu o pedido entrar e foi avisar". */
    public static int randomNotificacaoDelayMs() {
        return ThreadLocalRandom.current().nextInt(15_000, 90_001);
    }

    /** Verifica se é horário comercial pra notificações proativas.
     *  Não manda madrugada (0h-7h) nem muito tarde (>23h59).
     *  Reduz risco de incomodar cliente + flagar como bot. */
    public static boolean dentroHorarioNotificacao() {
        int h = LocalTime.now().getHour();
        return h >= 8 && h < 23;
    }
}
