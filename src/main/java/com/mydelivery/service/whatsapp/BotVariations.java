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
}
