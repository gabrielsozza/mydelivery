package com.mydelivery.service.myhelp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.mydelivery.model.BairroEntrega;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.ProdutoRepository;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.util.BairroNormalizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Motor do myHelp — assistente do dono, CUSTO ZERO (sem LLM). Entende linguagem
 * natural por regras + dicionário. Faz duas ações que mexem em dinheiro, sempre
 * com CARD de confirmação antes de aplicar:
 *  • Alterar preço de um produto (match fuzzy no cardápio real, KB de reforço).
 *  • Alterar a taxa de um bairro de entrega (confere na lista que a loja criou).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyHelpService {

    private final ProdutoRepository produtoRepo;
    private final RestauranteRepository restauranteRepo;
    private final MyHelpDominio dominio;
    private final CacheManager cacheManager;

    /** Slugs liberados no beta (vírgula). "*" libera todas. Vazio = ninguém. */
    @Value("${myhelp.beta-slugs:}")
    private String betaSlugs;

    private static final Pattern NUM = Pattern.compile("(\\d{1,5}(?:[.,]\\d{1,2})?)");
    private static final Pattern VERBO_ALTERA = Pattern.compile(
        "\\b(altera|alterar|muda|mudar|troca|trocar|coloca|colocar|poe|bota|botar|deixa|"
        + "atualiza|ajusta|ajustar|sobe|subir|aumenta|aumentar|abaixa|baixa|baixar|por)\\b");
    private static final Pattern INTENCAO_PRECO = Pattern.compile(
        "\\b(preco|precos|valor|valores|custa|custo)\\b");

    public boolean habilitado(Restaurante r) {
        if (betaSlugs == null || betaSlugs.isBlank() || r == null) return false;
        String slug = r.getSlug() == null ? "" : r.getSlug().trim();
        for (String s : betaSlugs.split(",")) {
            String t = s.trim();
            if (t.equals("*")) return true;
            if (!t.isEmpty() && t.equalsIgnoreCase(slug)) return true;
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Conversa
    // ═════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> responder(Restaurante r, String textoBruto) {
        String texto = textoBruto == null ? "" : textoBruto.trim();
        String norm = MyHelpTexto.norm(texto);
        if (norm.isBlank()) return texto(pick("Oi! Me diz o que você precisa 🙂", "Opa! Como posso te ajudar?"));

        boolean verbo = VERBO_ALTERA.matcher(norm).find();
        boolean temNum = NUM.matcher(norm).find();

        // ── AÇÕES primeiro (mexer em dinheiro tem prioridade sobre bate-papo) ──
        boolean falaBairro = norm.contains("bairro");
        boolean falaTaxa = norm.matches(".*\\b(taxa|frete)\\b.*");
        if (falaBairro || (falaTaxa && verbo)) return fluxoTaxaBairro(r, texto, norm);
        if (INTENCAO_PRECO.matcher(norm).find() || (verbo && temNum)) return fluxoPreco(r, texto, norm);

        // ── BATE-PAPO humano (oi, tudo bem, obrigado, como tá o dia...) ──
        Map<String, Object> humano = humano(norm);
        if (humano != null) return humano;

        // ── FAQ (dúvidas de uso do sistema) ──
        Map<String, Object> faq = faq(norm);
        if (faq != null) return faq;

        // Fallback amigável e variado (não robótico)
        return texto(pick(
            "Hmm, não peguei bem essa 🤔 Mas relaxa — me fala do seu jeito, tipo *\"muda o preço da coca lata pra 6\"* ou *\"altera a taxa do Centro pra 7\"*, que eu resolvo.",
            "Não entendi 100%, mas tô aqui pra ajudar 🙂 Posso mexer no preço de um produto ou na taxa de um bairro. O que você quer fazer?",
            "Me explica de outro jeito? 😅 Consigo alterar preço de produto, taxa de bairro e tirar dúvidas do sistema."));
    }

    /**
     * Base de conversa "humana" — responde saudações, papo casual e agradecimento
     * de forma espontânea e variada (custo zero, sem LLM). Retorna null se a
     * mensagem não é papo (aí o responder segue pra FAQ/fallback).
     */
    private Map<String, Object> humano(String norm) {
        // agradecimento
        if (norm.matches(".*\\b(obrigad\\w*|valeu|vlw|brigad\\w*|agradec\\w*|tmj|gratidao)\\b.*"))
            return texto(pick("Por nada! 😊 Tamo junto.", "Disponha! Qualquer coisa é só chamar. 🙌",
                "Imagina! Precisando, é só falar comigo.", "Tmj! 💪 Bora fazer essa loja render."));
        // tudo bem / como vai / beleza
        if (norm.matches(".*\\b(tudo bem|tudo bom|como vai|como voce esta|como ta voce|beleza|de boa|suave|blz|como vc ta)\\b.*"))
            return texto(pick(
                "Tudo ótimo por aqui 😄 e você? Como tá o movimento hoje?",
                "Tudo certo! 🙌 Pronto pra te ajudar. E aí, dia bom na loja?",
                "Tudo tranquilo! E com você, muita correria hoje? 😅"));
        // como tá o dia / movimento / vendas
        if (norm.matches(".*(como.*(dia|movimento|vai o dia)|e o movimento|vendas hoje|ta vendendo|muito pedido).*"))
            return texto(pick(
                "Tomara que o balcão esteja bombando hoje! 🚀 Se quiser dar um gás, eu ajusto um preço rapidinho pra você.",
                "Torcendo pra render bons pedidos! 💪 Precisando mexer em algo do cardápio, é só me falar.",
                "Espero que muito pedido pingando! 😄 Qualquer ajuste de preço ou taxa, tô na mão."));
        // quem é você / é robô / é humano
        if (norm.matches(".*(quem e voce|voce e quem|o que voce faz|pra que voce serve|voce e um bot|voce e robo|voce e humano|seu nome).*"))
            return texto("Sou o myHelp, seu parceiro aqui do MyDelivery 🤝 Fui feito pra te poupar tempo: você fala comigo em português normal e eu altero preço de produto, taxa de bairro e tiro dúvidas do sistema. Pode me tratar como aquele funcionário de confiança do balcão. 😉");
        // elogio
        if (norm.matches(".*\\b(voce e bom|gostei|muito bom|adorei|otimo|top|show|maneiro|legal|massa|sensacional|excelente)\\b.*"))
            return texto(pick("Aí sim! 😄 Fico feliz em ajudar.", "Valeu demais! 🙌 Bora fazer essa loja crescer.",
                "Que bom que curtiu! Tô aqui pro que precisar. 💪"));
        // despedida
        if (norm.matches(".*\\b(tchau|ate logo|ate mais|ate breve|falou|xau|fui|boa noite pra voce)\\b.*"))
            return texto(pick("Até mais! Boas vendas 👋", "Falou! Qualquer coisa é só me chamar. 🙌",
                "Tchau tchau! Tô por aqui quando precisar. 😊"));
        // pedido de ajuda / o que sabe fazer
        if (norm.matches(".*(ajuda|help|me ajuda|socorro|o que voce pode fazer|o que sabe fazer|menu|opcoes|comandos|como funciona).*"))
            return texto("Claro! 🙌 Eu consigo, por exemplo:\n• *\"muda o preço do X-Tudo pra 25\"*\n• *\"altera a taxa do bairro Centro pra 8\"*\n• tirar dúvidas de cardápio, horário, taxa de entrega e impressão.\nÉ só falar do seu jeito que eu entendo. 😉");
        // saudação (oi / olá / bom dia...)
        if (norm.matches(".*\\b(oi+|ola+|opa|eae|e ai|salve|bom dia|boa tarde|boa noite|hey|alo|iai)\\b.*"))
            return texto(pick(
                saudacaoHora() + "! 😄 Sou o myHelp. Como posso te ajudar? Dá pra mexer em preço, taxa de bairro ou tirar dúvida do sistema.",
                saudacaoHora() + ", chef! 👨‍🍳 Bora deixar a loja redonda? Me fala o que precisa.",
                "Opa, " + saudacaoHora().toLowerCase() + "! Tô por aqui — é só dizer o que quer ajustar. 🙂"));
        return null;
    }

    private String saudacaoHora() {
        int h;
        try { h = java.time.LocalTime.now(java.time.ZoneId.of("America/Sao_Paulo")).getHour(); }
        catch (Exception e) { h = java.time.LocalTime.now().getHour(); }
        if (h < 12) return "Bom dia";
        if (h < 18) return "Boa tarde";
        return "Boa noite";
    }

    private static final java.util.Random RND = new java.util.Random();
    private static String pick(String... opcoes) { return opcoes[RND.nextInt(opcoes.length)]; }

    // ── Fluxo: preço de produto ──────────────────────────────────────────
    private Map<String, Object> fluxoPreco(Restaurante r, String texto, String norm) {
        BigDecimal precoNovo = extrairPreco(texto);
        String consulta = extrairConsulta(norm, false);
        if (consulta.isBlank()) {
            return texto("Qual produto você quer alterar? Me diz o nome como está no cardápio "
                + "(ex.: *\"X-Bacon\"*, *\"Coca 2L\"*).");
        }
        List<Produto> produtos = produtoRepo.findByRestauranteId(r.getId());
        if (produtos.isEmpty()) {
            return texto("Você ainda não tem produtos no cardápio. Cadastre em *Cardápio* e depois eu te ajudo.");
        }

        Set<String> qTokens = MyHelpTexto.tokens(consulta);
        List<Pontuado> ranked = new ArrayList<>();
        for (Produto p : produtos) {
            String pNorm = MyHelpTexto.norm(p.getNome());
            int s = score(consulta, qTokens, pNorm, MyHelpTexto.tokens(pNorm));
            if (s > 0) ranked.add(new Pontuado(p, s));
        }
        ranked.sort(Comparator.comparingInt((Pontuado x) -> x.score).reversed());
        Pontuado best = ranked.isEmpty() ? null : ranked.get(0);
        Pontuado second = ranked.size() > 1 ? ranked.get(1) : null;

        if (best != null && best.score >= 70 && (second == null || best.score - second.score >= 20)) {
            if (precoNovo == null) {
                return texto("Achei o *" + best.p.getNome() + "* (hoje " + moeda(best.p.getPreco())
                    + "). Pra quanto você quer mudar?");
            }
            return card("preco", produtoItem(best.p, precoNovo),
                "Confere e confirma que eu altero o preço:");
        }

        List<Produto> cand = new ArrayList<>();
        for (Pontuado x : ranked) if (x.score >= 40 && cand.size() < 6) cand.add(x.p);
        if (cand.isEmpty()) {
            String tag = dominio.inferirCategoria(consulta);
            if (tag != null) for (Produto p : produtos) {
                if (tag.equals(dominio.inferirCategoria(MyHelpTexto.norm(p.getNome()))) && cand.size() < 6) cand.add(p);
            }
        }
        if (!cand.isEmpty()) {
            List<Map<String, Object>> ops = new ArrayList<>();
            for (Produto p : cand) ops.add(produtoItem(p, precoNovo));
            String msg = precoNovo != null
                ? "Não achei um produto específico pra \"" + consulta + "\". É algum destes? Toque pra alterar pra " + moeda(precoNovo) + ":"
                : "É algum destes? (depois me diga o novo preço)";
            return escolha("preco", ops, msg);
        }
        return texto("Não encontrei nada parecido com \"" + consulta + "\" no seu cardápio 😕 "
            + "Tenta o nome como está cadastrado, ou abre a aba *Cardápio*.");
    }

    // ── Fluxo: taxa de bairro ────────────────────────────────────────────
    private Map<String, Object> fluxoTaxaBairro(Restaurante r, String texto, String norm) {
        List<BairroEntrega> bairros = r.getBairrosAtendidos();
        if (bairros == null || bairros.isEmpty()) {
            return texto("Você ainda não tem bairros cadastrados. Cadastre em *Configurações → Taxa de entrega "
                + "(por bairro)* e depois eu ajusto as taxas pra você.");
        }
        BigDecimal taxaNova = extrairPreco(texto);
        String consulta = extrairConsulta(norm, true);

        // Acha os bairros cadastrados que casam com o que o dono falou.
        List<BairroEntrega> matches = new ArrayList<>();
        for (BairroEntrega b : bairros) {
            if (b.getNome() == null) continue;
            String bNorm = MyHelpTexto.norm(b.getNome());
            boolean casa = (!consulta.isBlank() && BairroNormalizer.combina(b.getNome(), consulta))
                || (bNorm.length() >= 4 && (" " + norm + " ").contains(" " + bNorm + " "))
                || (bNorm.length() >= 4 && norm.contains(bNorm));
            if (casa) matches.add(b);
        }

        if (matches.size() == 1) {
            BairroEntrega b = matches.get(0);
            if (taxaNova == null) {
                return texto("Achei o bairro *" + b.getNome() + "* (taxa hoje " + moeda(b.getTaxa())
                    + "). Pra quanto você quer mudar?");
            }
            return card("taxaBairro", bairroItem(b, taxaNova),
                "Confere e confirma que eu altero a taxa deste bairro:");
        }
        if (matches.size() > 1) {
            List<Map<String, Object>> ops = new ArrayList<>();
            for (BairroEntrega b : matches) if (ops.size() < 8) ops.add(bairroItem(b, taxaNova));
            return escolha("taxaBairro", ops,
                taxaNova != null ? "Achei mais de um. Qual bairro? Toque pra alterar pra " + moeda(taxaNova) + ":"
                                 : "Qual bairro? (depois me diga a nova taxa)");
        }

        // Não casou — lista os bairros cadastrados pra escolher
        List<Map<String, Object>> ops = new ArrayList<>();
        for (BairroEntrega b : bairros) if (ops.size() < 12) ops.add(bairroItem(b, taxaNova));
        String pref = consulta.isBlank()
            ? "Qual bairro você quer alterar? Estes são os que você tem cadastrados"
            : "Não achei o bairro \"" + consulta + "\" na sua lista. Você tem estes";
        return escolha("taxaBairro", ops, pref + (taxaNova != null ? " (toque pra pôr " + moeda(taxaNova) + "):" : ":"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // Confirmação (aplica de fato)
    // ═════════════════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> confirmarPreco(Restaurante r, Long produtoId, BigDecimal precoNovo) {
        if (produtoId == null || precoNovo == null) return texto("Faltou o produto ou o preço 🤔");
        if (!precoValido(precoNovo)) return texto("Esse valor não parece certo. Me diga um preço válido 🙂");
        Produto p = produtoRepo.findById(produtoId).orElse(null);
        if (p == null || p.getRestaurante() == null || !p.getRestaurante().getId().equals(r.getId())) {
            return texto("Não achei esse produto na sua loja 😕");
        }
        BigDecimal antigo = p.getPreco();
        p.setPreco(precoNovo);
        produtoRepo.save(p);
        // Cardápio público fica em cache (60s). Sem invalidar, o cliente veria o
        // preço velho depois do myHelp dizer "alterei ✅". Limpa após o commit.
        invalidarCachePublico(r.getSlug());
        log.info("[myHelp] loja={} preco produto={} {} -> {}", r.getSlug(), p.getId(), antigo, precoNovo);
        Map<String, Object> out = texto("Pronto! ✅ *" + p.getNome() + "* agora está " + moeda(precoNovo)
            + (antigo != null ? " (era " + moeda(antigo) + ")" : "") + ".");
        out.put("alterado", true);
        return out;
    }

    @Transactional
    public Map<String, Object> confirmarTaxaBairro(Restaurante r, String bairroNome, BigDecimal taxaNova) {
        if (bairroNome == null || bairroNome.isBlank() || taxaNova == null) return texto("Faltou o bairro ou a taxa 🤔");
        if (taxaNova.signum() < 0 || taxaNova.compareTo(new BigDecimal("100000")) > 0) {
            return texto("Essa taxa não parece certa. Me diga um valor válido 🙂");
        }
        Restaurante rr = restauranteRepo.findById(r.getId()).orElse(r);
        BairroEntrega alvo = null;
        for (BairroEntrega b : rr.getBairrosAtendidos()) {
            if (b.getNome() != null && BairroNormalizer.combina(b.getNome(), bairroNome)) { alvo = b; break; }
        }
        if (alvo == null) return texto("Não achei o bairro *" + bairroNome + "* na sua lista 😕");
        BigDecimal antiga = alvo.getTaxa();
        alvo.setTaxa(taxaNova);
        restauranteRepo.save(rr);
        invalidarCachePublico(rr.getSlug());
        log.info("[myHelp] loja={} taxa bairro='{}' {} -> {}", rr.getSlug(), alvo.getNome(), antiga, taxaNova);
        Map<String, Object> out = texto("Pronto! ✅ A taxa do bairro *" + alvo.getNome() + "* agora é "
            + moeda(taxaNova) + (antiga != null ? " (era " + moeda(antiga) + ")" : "") + ".");
        out.put("alterado", true);
        return out;
    }

    /**
     * Invalida o cache do cardápio público da loja DEPOIS que a transação commitar.
     * Se limpasse antes do commit, uma leitura concorrente repopularia o cache com
     * o dado antigo (ainda não commitado) — voltando a bug. Por isso usa
     * afterCommit; sem transação ativa, limpa na hora.
     */
    private void invalidarCachePublico(String slug) {
        Runnable limpar = () -> {
            Cache cardapio = cacheManager.getCache("cardapio");
            if (cardapio != null && slug != null) {
                // chave é "slug::DIA_DA_SEMANA" — só o dia de hoje existe na prática,
                // mas limpo os 7 por segurança (barato).
                for (java.time.DayOfWeek d : java.time.DayOfWeek.values()) {
                    cardapio.evict(slug + "::" + d.name());
                }
            }
            // Config pública (taxa/bairros/banners) — caches pequenos, limpa geral.
            Cache rp = cacheManager.getCache("restaurante-publico");
            if (rp != null) rp.clear();
            Cache banners = cacheManager.getCache("cardapio-banners");
            if (banners != null) banners.clear();
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { limpar.run(); }
            });
        } else {
            limpar.run();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Heurísticas
    // ═════════════════════════════════════════════════════════════════════

    private BigDecimal extrairPreco(String texto) {
        String t = texto.toLowerCase();
        Matcher mPos = Pattern.compile("(?:pra|para|por|=|:)\\s*r?\\$?\\s*" + NUM.pattern()).matcher(t);
        String achado = null;
        if (mPos.find()) achado = mPos.group(1);
        if (achado == null) {
            Matcher m = NUM.matcher(t);
            while (m.find()) achado = m.group(1);
        }
        if (achado == null) return null;
        try {
            if (achado.contains(",")) return new BigDecimal(achado.replace(".", "").replace(",", "."));
            return new BigDecimal(achado);
        } catch (Exception e) { return null; }
    }

    /** Sobra o nome procurado (produto ou bairro) após remover comando/preço.
     *  MANTÉM números de TAMANHO (ex.: "açaí 300") — só o preço é removido, pra
     *  o "300" ajudar a casar "Açaí 300ml" em vez de listar todos os açaís. */
    private String extrairConsulta(String norm, boolean bairro) {
        String s = norm;
        Matcher md = Pattern.compile("\\b(pra|para|por|=)\\b").matcher(norm);
        if (md.find()) {
            s = norm.substring(0, md.start());                 // corta em "... pra 20 reais"
        } else {
            // Sem "pra": remove só o ÚLTIMO número (o preço) + moeda ao fim.
            s = s.replaceAll("\\s\\d+(?:[.,]\\d+)?\\s*(?:reais|real|reis|riais|riaes|rs|conto|pila)?\\s*$", " ");
        }
        StringBuilder sb = new StringBuilder();
        for (String tok : s.split(" ")) {
            if (tok.isBlank()) continue;
            if (MyHelpTexto.STOPWORDS.contains(tok)) continue; // números (tamanho) são mantidos
            if (bairro && (tok.equals("taxa") || tok.equals("frete") || tok.equals("bairro") || tok.equals("entrega"))) continue;
            sb.append(tok).append(' ');
        }
        return sb.toString().trim();
    }

    private int score(String qNorm, Set<String> qTokens, String pNorm, Set<String> pTokens) {
        if (qNorm.isBlank() || pNorm.isBlank()) return 0;
        if (pNorm.equals(qNorm)) return 100;
        int s = 0;
        if (pNorm.contains(qNorm) || qNorm.contains(pNorm)) s = 75;
        if (!qTokens.isEmpty()) {
            int comum = 0;
            for (String qt : qTokens) {
                boolean casa = pTokens.contains(qt);
                if (!casa && qt.length() >= 3) {
                    // "300" casa "300ml", "coca" casa "cocacola" — substring de token
                    for (String pt : pTokens) { if (pt.contains(qt) || qt.contains(pt)) { casa = true; break; } }
                }
                if (casa) comum++;
            }
            s = Math.max(s, (int) Math.round(72.0 * comum / qTokens.size()));
        }
        if (qTokens.size() == 1) {
            String q = qTokens.iterator().next();
            for (String pt : pTokens) {
                if (Math.abs(pt.length() - q.length()) <= 2) {
                    int d = MyHelpTexto.levenshtein(q, pt);
                    if (d <= 1) s = Math.max(s, 85);
                    else if (d == 2 && q.length() >= 5) s = Math.max(s, 55);
                }
            }
        }
        return s;
    }

    private Map<String, Object> faq(String norm) {
        if (norm.matches(".*\\b(adicionar|cadastrar|criar|novo)\\b.*\\b(produto|item|lanche)\\b.*")) {
            return texto("Pra adicionar um produto: aba *Cardápio* → *+ Novo produto* → nome, preço e foto → *Salvar*.");
        }
        if (norm.matches(".*\\bhorario\\b.*") || norm.matches(".*\\b(abrir|fechar) a loja\\b.*")) {
            return texto("Horário fica em *Configurações → Horários*. A loja abre e fecha sozinha nesses horários.");
        }
        if (norm.matches(".*\\bimprim\\w*\\b.*") || norm.matches(".*\\bimpressora\\b.*")) {
            return texto("Impressão fica em *Configurações → Impressora térmica*. No PC/Mac use o App MyDelivery; "
                + "no tablet Android com impressora Bluetooth, o RawBT.");
        }
        return null;
    }

    // ── Builders ─────────────────────────────────────────────────────────
    private Map<String, Object> texto(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", "texto");
        m.put("mensagem", msg);
        return m;
    }
    private Map<String, Object> card(String acao, Map<String, Object> item, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", "card"); m.put("acao", acao); m.put("mensagem", msg); m.put("item", item);
        return m;
    }
    private Map<String, Object> escolha(String acao, List<Map<String, Object>> ops, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", "escolha"); m.put("acao", acao); m.put("mensagem", msg); m.put("opcoes", ops);
        return m;
    }
    private Map<String, Object> produtoItem(Produto p, BigDecimal precoNovo) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("acao", "preco");
        c.put("produtoId", p.getId());
        c.put("titulo", p.getNome());
        c.put("fotoUrl", p.getFotoUrl());
        c.put("atualFmt", moeda(p.getPreco()));
        if (precoNovo != null) { c.put("precoNovo", precoNovo); c.put("novoFmt", moeda(precoNovo)); }
        return c;
    }
    private Map<String, Object> bairroItem(BairroEntrega b, BigDecimal taxaNova) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("acao", "taxaBairro");
        c.put("bairroNome", b.getNome());
        c.put("titulo", "Bairro: " + b.getNome());
        c.put("atualFmt", moeda(b.getTaxa()));
        if (taxaNova != null) { c.put("taxaNova", taxaNova); c.put("novoFmt", moeda(taxaNova)); }
        return c;
    }

    private static boolean precoValido(BigDecimal v) {
        return v != null && v.signum() > 0 && v.compareTo(new BigDecimal("100000")) <= 0;
    }
    private static String moeda(BigDecimal v) {
        if (v == null) return "R$ 0,00";
        return "R$ " + v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace(".", ",");
    }
    private static final class Pontuado {
        final Produto p; final int score;
        Pontuado(Produto p, int score) { this.p = p; this.score = score; }
    }
}
