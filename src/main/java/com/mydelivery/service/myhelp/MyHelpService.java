package com.mydelivery.service.myhelp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
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
import com.mydelivery.model.Categoria;
import com.mydelivery.model.ComplementoGrupo;
import com.mydelivery.model.ComplementoItem;
import com.mydelivery.model.MyHelpMemoria;
import com.mydelivery.model.Produto;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.CategoriaRepository;
import com.mydelivery.repository.ComplementoGrupoRepository;
import com.mydelivery.repository.ComplementoItemRepository;
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
    private final CategoriaRepository categoriaRepo;
    private final ComplementoGrupoRepository grupoRepo;
    private final ComplementoItemRepository itemRepo;
    private final MyHelpDominio dominio;
    private final MyHelpMemoriaService memoria;
    private final CacheManager cacheManager;

    /** Memória curta de conversa: guarda a pendência (o que o myHelp perguntou e
     *  está esperando resposta) por loja, por poucos minutos. Assim "qual a
     *  descrição?" → a próxima mensagem já é entendida como a resposta. */
    private final com.github.benmanes.caffeine.cache.Cache<Long, Map<String, Object>> pendencia =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(5, java.util.concurrent.TimeUnit.MINUTES)
                    .maximumSize(5000).build();

    /** Último produto falado por loja — resolve "coloca ELE por 29,90". */
    private final com.github.benmanes.caffeine.cache.Cache<Long, String> ultimoAlvo =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(10, java.util.concurrent.TimeUnit.MINUTES)
                    .maximumSize(5000).build();
    private static final Pattern PRONOME = Pattern.compile(
            "^(ele|ela|esse|essa|isso|isto|o mesmo|a mesma|dele|dela|nele|nela|mesmo)$");

    /** Último TERMO que o dono mencionou (mesmo sem casar) — pra corrigir depois
     *  ("não, era Peixe Frito" → ensina "peixe → Peixe Frito"). */
    private final com.github.benmanes.caffeine.cache.Cache<Long, String> ultimoTermo =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(10, java.util.concurrent.TimeUnit.MINUTES)
                    .maximumSize(5000).build();

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
        Map<String, Object> resp = responderInterno(r, textoBruto);
        // Observabilidade: log enxuto do que a IA entendeu (pra debugar erros).
        try {
            log.info("[myHelp-log] loja={} input=\"{}\" tipo={} acao={}",
                r == null ? null : r.getSlug(), textoBruto,
                resp == null ? null : resp.get("tipo"),
                resp == null ? null : resp.get("acao"));
        } catch (Exception ignore) {}
        return resp;
    }

    private Map<String, Object> responderInterno(Restaurante r, String textoBruto) {
        String texto = textoBruto == null ? "" : textoBruto.trim();
        String norm = MyHelpTexto.norm(texto);
        if (norm.isBlank()) return texto(pick("Oi! Me diz o que você precisa 🙂", "Opa! Como posso te ajudar?"));

        boolean verbo = VERBO_ALTERA.matcher(norm).find();
        boolean temNum = NUM.matcher(norm).find();

        // ── AÇÕES primeiro (mexer no cardápio/dinheiro tem prioridade) ──
        boolean falaBairro = norm.contains("bairro");
        boolean falaTaxa = norm.matches(".*\\b(taxa|frete)\\b.*");
        if (falaBairro || (falaTaxa && verbo)) return fluxoTaxaBairro(r, texto, norm);

        // Sinais das novas ações de cardápio
        boolean criar = norm.matches(".*\\b(cria|criar|crie|criando|nova|novo|adiciona|adicionar|adicione|cadastra|cadastrar|cadastre|inclui|incluir|inclua|registra|registrar)\\b.*");
        boolean falaGrupo = norm.matches(".*\\bgrupo(s)?\\b.*");
        boolean grupoLocal = norm.matches(".*\\b(no|do|ao|na|pro|dentro do) grupo\\b.*");     // grupo como DESTINO
        boolean falaComplemento = norm.matches(".*\\b(complemento|complementos|adicional|adicionais|opcao|opcoes|opcional)\\b.*");
        boolean falaCategoria = norm.matches(".*\\bcategoria(s)?\\b.*");
        boolean categoriaLocal = norm.matches(".*\\b(na|em|pra|para) categoria\\b.*");         // categoria como LUGAR do produto
        boolean falaDescricao = norm.matches(".*\\b(descricao|descric\\w*)\\b.*");
        boolean falaNome = norm.matches(".*\\b(nome|titulo|renomeia\\w*|renomear|rebatiza\\w*|apelido|chama)\\b.*");
        boolean falaProduto = norm.matches(".*\\b(produto|item|lanche|prato)\\b.*");
        boolean falaPromo = norm.matches(".*\\b(promoc\\w*|promo|desconto|oferta)\\b.*");

        // ── Memória de contexto: se estou esperando um valor e a mensagem é
        //    "só o valor" (sem começar um comando novo), uso a pendência. ──
        boolean novoComando = falaBairro || falaTaxa || criar || falaGrupo || falaComplemento
            || falaCategoria || falaDescricao || falaNome || falaProduto || falaPromo
            || INTENCAO_PRECO.matcher(norm).find();
        Map<String, Object> pend = pendencia.getIfPresent(r.getId());
        if (pend != null) {
            pendencia.invalidate(r.getId());              // usa uma vez só
            if (!novoComando) return resolverPendencia(r, pend, texto);
        }

        // ── Ensino/correção explícito ("quando eu falar X é Y") → memória (com card) ──
        Map<String, Object> ensino = fluxoAprender(r, texto, norm);
        if (ensino != null) return ensino;

        // Complementos (grupo / item) — os mais específicos
        if (falaGrupo && criar && !grupoLocal) return fluxoNovoGrupo(r, texto, norm);
        if (criar && (grupoLocal || (falaComplemento && !falaGrupo))) return fluxoNovoComplemento(r, texto, norm);
        if (falaComplemento && (INTENCAO_PRECO.matcher(norm).find() || (verbo && temNum)))
            return fluxoPrecoComplemento(r, texto, norm);
        // Categoria
        if (falaCategoria && criar && !categoriaLocal) return fluxoNovaCategoria(r, texto, norm);
        if (falaCategoria && !categoriaLocal && (falaNome || verbo)) return fluxoNomeCategoria(r, texto, norm);
        // Produto — criar (padrão de "cria X" quando não é grupo/categoria/promo) / descrição / nome
        if (criar && !falaPromo) return fluxoNovoProduto(r, texto, norm);
        if (falaDescricao) return fluxoDescricaoProduto(r, texto, norm);
        if (falaNome && !falaCategoria) return fluxoNomeProduto(r, texto, norm);

        // Promoção — ANTES do preço (usa precoOriginal, preserva o preço normal)
        if (falaPromo) return fluxoPromocao(r, texto, norm);

        // Preço de produto (catch-all de dinheiro) — por último das ações
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
        // Se falou "produto X" explícito, o alvo é X; senão, o resto da frase.
        String consulta = norm.matches(".*\\bproduto\\b.*") ? alvoProduto(texto, norm) : extrairConsulta(norm, false);
        lembrarTermo(r.getId(), consulta);                        // pra corrigir depois ("não, era Y")
        consulta = resolverPronome(r.getId(), consulta);          // contexto: "coloca ele por 25"
        consulta = resolverApelidoProduto(r.getId(), consulta);   // memória: apelidos aprendidos
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
                pendencia.put(r.getId(), mp("acao", "precoProduto", "produtoId", best.p.getId()));
                return texto("Achei o *" + best.p.getNome() + "* (hoje " + moeda(best.p.getPreco())
                    + "). Pra quanto você quer mudar?");
            }
            return card("preco", produtoItem(best.p, precoNovo),
                "Confere e confirma que eu altero o preço:");
        }

        // Candidatos por match REAL no nome (fuzzy/substring). Se há match de
        // nome, NUNCA dizer "não achei" — ele achou, só tem mais de um.
        List<Produto> cand = new ArrayList<>();
        for (Pontuado x : ranked) if (x.score >= 40 && cand.size() < 6) cand.add(x.p);
        boolean porNome = !cand.isEmpty();
        if (cand.isEmpty()) {
            // Nenhum match no nome — última tentativa: mesma categoria do domínio.
            String tag = dominio.inferirCategoria(consulta);
            if (tag != null) for (Produto p : produtos) {
                if (tag.equals(dominio.inferirCategoria(MyHelpTexto.norm(p.getNome()))) && cand.size() < 6) cand.add(p);
            }
        }
        if (!cand.isEmpty()) {
            List<Map<String, Object>> ops = new ArrayList<>();
            for (Produto p : cand) ops.add(produtoItem(p, precoNovo));
            String msg;
            if (porNome) {
                // Achou de verdade (só há mais de um parecido) — framing positivo.
                msg = precoNovo != null
                    ? "Achei estes que combinam com *" + consulta + "*. Toque no certo pra deixar em " + moeda(precoNovo) + ":"
                    : "Achei estes que combinam com *" + consulta + "*. Qual é? (depois me diga o novo preço)";
            } else {
                // Só sugestão por categoria — aí sim é honesto dizer "não achei exato".
                msg = precoNovo != null
                    ? "Não achei um *" + consulta + "* exato, mas talvez seja um destes. Toque pra deixar em " + moeda(precoNovo) + ":"
                    : "Não achei um *" + consulta + "* exato. Seria um destes?";
            }
            return escolha("preco", ops, msg);
        }
        return texto("Não encontrei nada parecido com *" + consulta + "* no seu cardápio 😕 "
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
    // NOVAS AÇÕES DE CARDÁPIO (título, descrição, categoria, produto, complemento)
    // Todas mostram card com "de → para" (ou resumo do que vai criar) e só
    // aplicam depois do confirmar. Palavras que descartar ao extrair o alvo.
    // ═════════════════════════════════════════════════════════════════════
    private static final Set<String> STOP_ACAO = new HashSet<>(Arrays.asList(
        "cria","criar","crie","criando","nova","novo","novos","novas","adiciona","adicionar","adicione",
        "cadastra","cadastrar","cadastre","inclui","incluir","inclua","registra","registrar","edita","editar",
        "nome","titulo","renomeia","renomear","rebatiza","apelido","chama","chamar","chamado","chamada",
        "descricao","descricoes","produto","produtos","item","itens","lanche","prato","categoria","categorias",
        "grupo","grupos","complemento","complementos","adicional","adicionais","opcao","opcoes","opcional","dentro"));

    // ── Título de produto ────────────────────────────────────────────────
    private Map<String, Object> fluxoNomeProduto(Restaurante r, String texto, String norm) {
        // valor: depois de "pra", senão inline depois de "nome/chamado/titulo".
        String nomeNovo = textoNovoAposPra(texto);
        if (nomeNovo == null) nomeNovo = fatiaEntre(texto, "(chamad\\w*|nome|titulo)", "(no produto|do produto|produto|pra|para)");
        String consulta = alvoProduto(texto, norm);
        lembrarTermo(r.getId(), consulta);
        if (consulta.isBlank())
            return texto("Qual produto você quer renomear? Ex.: *\"muda o nome do X-Tudo pra X-Bacon\"*.");
        List<Produto> cand = new ArrayList<>();
        Produto p = melhorProduto(r.getId(), consulta, cand);
        if (p == null) {
            if (cand.isEmpty()) return texto("Não achei *" + consulta + "* no seu cardápio 😕");
            List<Map<String, Object>> ops = new ArrayList<>();
            for (Produto x : cand) ops.add(item("nomeProduto", x.getNome(), x.getFotoUrl(), "tag",
                x.getNome(), nomeNovo, "muda o nome do " + x.getNome() + " pra ", mp("produtoId", x.getId(), "textoNovo", nomeNovo)));
            return escolha("nomeProduto", ops, nomeNovo != null ? "Qual produto vira *" + nomeNovo + "*?" : "Qual produto você quer renomear?");
        }
        if (nomeNovo == null || nomeNovo.isBlank()) {
            pendencia.put(r.getId(), mp("acao", "nomeProduto", "produtoId", p.getId()));
            return texto("Certo, o *" + p.getNome() + "*. Qual vai ser o novo nome?");
        }
        return card("nomeProduto", item("nomeProduto", p.getNome(), p.getFotoUrl(), "tag",
            p.getNome(), nomeNovo, null, mp("produtoId", p.getId(), "textoNovo", nomeNovo)),
            "Confere a troca de nome e confirma:");
    }

    // ── Descrição de produto ─────────────────────────────────────────────
    private Map<String, Object> fluxoDescricaoProduto(Restaurante r, String texto, String norm) {
        // valor: depois de "pra", senão inline depois de "descrição ...".
        String descNova = textoNovoAposPra(texto);
        if (descNova == null) descNova = fatiaEntre(texto, "descric\\w*", "(no produto|do produto|o produto|produto|pra|para)");
        String consulta = alvoProduto(texto, norm);
        lembrarTermo(r.getId(), consulta);
        if (consulta.isBlank())
            return texto("Qual produto você quer descrever? Ex.: *\"muda a descrição do X-Tudo pra pão, 2 carnes, queijo e bacon\"*.");
        List<Produto> cand = new ArrayList<>();
        Produto p = melhorProduto(r.getId(), consulta, cand);
        if (p == null) {
            if (cand.isEmpty()) return texto("Não achei *" + consulta + "* no seu cardápio 😕");
            List<Map<String, Object>> ops = new ArrayList<>();
            for (Produto x : cand) ops.add(item("descProduto", x.getNome(), x.getFotoUrl(), "tag",
                descTexto(x.getDescricao()), descNova, "muda a descrição do " + x.getNome() + " pra ", mp("produtoId", x.getId(), "textoNovo", descNova)));
            return escolha("descProduto", ops, "De qual produto é essa descrição?");
        }
        if (descNova == null || descNova.isBlank()) {
            pendencia.put(r.getId(), mp("acao", "descProduto", "produtoId", p.getId()));
            return texto("Beleza, o *" + p.getNome() + "*. Qual vai ser a nova descrição?");
        }
        return card("descProduto", item("descProduto", p.getNome(), p.getFotoUrl(), "tag",
            descTexto(p.getDescricao()), descNova, null, mp("produtoId", p.getId(), "textoNovo", descNova)),
            "Confere a nova descrição e confirma:");
    }

    // ── Título de categoria ──────────────────────────────────────────────
    private Map<String, Object> fluxoNomeCategoria(Restaurante r, String texto, String norm) {
        String nomeNovo = textoNovoAposPra(texto);
        String consulta = alvoConsulta(norm, STOP_ACAO);
        if (consulta.isBlank())
            return texto("Qual categoria você quer renomear? Ex.: *\"muda o nome da categoria Bebidas pra Bebidas Geladas\"*.");
        List<Categoria> cand = new ArrayList<>();
        Categoria c = melhorCategoria(r.getId(), consulta, cand);
        if (c == null) {
            if (cand.isEmpty()) return texto("Não achei a categoria *" + consulta + "* 😕");
            List<Map<String, Object>> ops = new ArrayList<>();
            for (Categoria x : cand) ops.add(item("nomeCategoria", x.getNome(), null, "folder",
                x.getNome(), nomeNovo, "muda o nome da categoria " + x.getNome() + " pra ", mp("categoriaId", x.getId(), "textoNovo", nomeNovo)));
            return escolha("nomeCategoria", ops, nomeNovo != null ? "Qual categoria vira *" + nomeNovo + "*?" : "Qual categoria você quer renomear?");
        }
        if (nomeNovo == null) return texto("Certo, a categoria *" + c.getNome() + "*. Qual vai ser o novo nome?");
        return card("nomeCategoria", item("nomeCategoria", c.getNome(), null, "folder",
            c.getNome(), nomeNovo, null, mp("categoriaId", c.getId(), "textoNovo", nomeNovo)),
            "Confere o novo nome da categoria e confirma:");
    }

    // ── Preço de um complemento (item dentro de um grupo) ────────────────
    private Map<String, Object> fluxoPrecoComplemento(Restaurante r, String texto, String norm) {
        BigDecimal precoNovo = extrairPreco(texto);
        String consulta = alvoConsulta(norm, STOP_ACAO);
        if (consulta.isBlank())
            return texto("Qual complemento? Ex.: *\"muda o preço do complemento Bacon pra 4\"*.");
        List<ComplementoItem> cand = new ArrayList<>();
        ComplementoItem it = melhorComplemento(r.getId(), consulta, cand);
        if (it == null) {
            if (cand.isEmpty()) return texto("Não achei o complemento *" + consulta + "* nos seus produtos 😕");
            List<Map<String, Object>> ops = new ArrayList<>();
            for (ComplementoItem x : cand) ops.add(itemComplemento(x, precoNovo));
            return escolha("precoComplemento", ops, precoNovo != null ? "Qual complemento? Toque pra pôr " + moeda(precoNovo) + ":" : "Qual complemento? (depois me diga o preço)");
        }
        if (precoNovo == null) return texto("Achei o complemento *" + it.getNome() + "* (hoje " + moeda(it.getPrecoAdicional()) + "). Pra quanto muda?");
        return card("precoComplemento", itemComplemento(it, precoNovo), "Confere e confirma o preço do complemento:");
    }

    // ── Nova categoria ───────────────────────────────────────────────────
    private Map<String, Object> fluxoNovaCategoria(Restaurante r, String texto, String norm) {
        // Nome: prefere depois de "chamada/com o nome/denominada"; senão depois de "categoria".
        String nome = fatiaEntre(texto, "(chamad\\w*|com o nome|denominad\\w*)", null);
        if (nome == null || nome.isBlank()) nome = fatiaEntre(texto, "categoria", "(chamad\\w*|com o nome|denominad\\w*)");
        if (nome == null || nome.isBlank()) nome = fatiaEntre(texto, "categoria", null);
        if (nome == null || nome.isBlank()) {
            pendencia.put(r.getId(), mp("acao", "novaCategoria"));
            return texto("Qual o nome da nova categoria? Ex.: *\"Sobremesas\"*.");
        }
        return card("novaCategoria", item("novaCategoria", "Nova categoria", null, "folder",
            null, nome, null, mp("textoNovo", nome)),
            "Vou criar esta categoria nova. Confirma?");
    }

    // ── Aprendizado: dono ENSINA ("quando eu falar X é Y", "considera X como Y")
    //    ou CORRIGE o último item ("não, era Y", "considera só Y"). O gatilho pode
    //    vir por pronome ("quando eu falar ISSO") → resolvido pro último termo. ──
    private static final Pattern PRONOME_ENSINO = Pattern.compile(
        "^(isso|isto|esse|essa|este|esta|assim|nesse caso|nesses casos|neste caso|desse jeito|dessa forma|ele|ela)$");

    private Map<String, Object> fluxoAprender(Restaurante r, String texto, String norm) {
        String[] par = detectarEnsino(texto);
        if (par == null) return null;
        String gatilho = par[0];
        String valor = limpaValorEnsino(par[1]);
        if (valor == null || valor.isBlank() || isPronome(valor)) return null;   // nada útil pra guardar
        // valor que é só número/preço NÃO é apelido ("não, era 25" = correção de preço, não ensino).
        if (valor.matches("(?i)^r?\\$?\\s*\\d+(?:[.,]\\d+)?\\s*(?:reais|real|conto|pila)?$") || valor.length() > 60) return null;
        // Gatilho ausente (correção) ou pronome ("isso") → usa o último termo falado.
        if (gatilho == null || gatilho.isBlank() || isPronome(MyHelpTexto.norm(gatilho))) {
            String ult = ultimoTermo.getIfPresent(r.getId());
            if (ult == null || ult.isBlank())
                return texto("Beleza, entendi que você quer me ensinar 🙂 mas *quando você falar o quê*? "
                    + "Me diz assim: *\"quando eu falar coca, é Coca-Cola 350ml\"*.");
            gatilho = ult;
        }
        gatilho = limpaNome(gatilho);
        if (gatilho == null || gatilho.isBlank() || isPronome(MyHelpTexto.norm(gatilho)))
            return texto("Me diz a palavra que você quer que eu aprenda? Ex.: *\"quando eu falar coca, é Coca-Cola 350ml\"*.");
        return card("aprender", item("aprender", "Aprender", null, "bulb",
            null, "\"" + gatilho + "\" → " + valor, null,
            mp("gatilho", gatilho, "valor", valor, "contexto", "product_ref")),
            "Quer que eu lembre disso pra essa loja?");
    }

    private static boolean isPronome(String s) {
        return s != null && PRONOME_ENSINO.matcher(s.trim()).matches();
    }

    /** Extrai [gatilho, valor] de um ensino OU correção (gatilho pode ser null = usar contexto). */
    private String[] detectarEnsino(String texto) {
        String t = texto == null ? "" : texto.trim();
        String conn = "(?:eh|é|e|significa|quer dizer|considera\\w*|considere|entenda?|entende|pode considerar|leva em conta|to falando d[eo]|estou falando d[eo]|me refiro a|equivale a|seria|é pra\\w*\\s+considerar|era pra\\w*\\s+considerar)";
        // 1) "quando/toda vez/sempre que eu falar X <conn> Y"
        Matcher m = Pattern.compile("(?i)(?:quando|toda vez que|sempre que|todas as vezes que|se|caso)\\s+eu\\s+(?:falar|falo|fala|digo|disser|diga|mencionar|menciono|pedir|pe[çc]o|escrever|escrevo)\\s+(.+?)[,]?\\s+" + conn + "\\s+(.+)$").matcher(t);
        if (m.find()) return new String[]{ limpaNome(m.group(1)), m.group(2) };
        // 2) INVERTIDO: "<valor> quando eu falar X"  (X pode ser pronome "isso")
        m = Pattern.compile("(?i)(.+?)[,]?\\s+(?:quando|toda vez que|sempre que|todas as vezes que)\\s+eu\\s+(?:falar|falo|digo|disser|diga|mencionar|escrever)\\s+(.+)$").matcher(t);
        if (m.find()) return new String[]{ limpaNome(m.group(2)), m.group(1) };
        // 3) "considera/entenda/interpreta X como/= Y"
        m = Pattern.compile("(?i)\\b(?:considera\\w*|considere|entenda?|entende|interpreta\\w*|assume|assuma|le[a]? em conta|leve em conta)\\s+(?:que\\s+)?(.+?)\\s+(?:como|=)\\s+(.+)$").matcher(t);
        if (m.find()) return new String[]{ limpaNome(m.group(1)), m.group(2) };
        // 4) "X significa/quer dizer Y"
        m = Pattern.compile("(?i)^(?:o |a )?(.+?)\\s+(?:significa|quer dizer|equivale a|é a mesma coisa que|é sin[oô]nimo de|é igual a)\\s+(.+)$").matcher(t);
        if (m.find()) return new String[]{ limpaNome(m.group(1)), m.group(2) };
        // 5) CORREÇÃO do último ("não, era Y" / "na verdade é Y" / "considera só Y")
        m = Pattern.compile("(?i)^(?:n[ãa]o|nops|opa|olha|calma|ei)[,.\\s]+(?:é|eh|era|na verdade\\s+é|na verdade|o certo\\s+é|o nome\\s+(?:certo\\s+)?é|o produto\\s+é|a categoria\\s+é|quis dizer|queria dizer|seria|considera\\s+s[óo]|considere\\s+s[óo]|considera\\s+apenas|é\\s+s[óo]|é\\s+apenas|pra\\w*\\s+considerar)\\s+(.+)$").matcher(t);
        if (m.find()) return new String[]{ null, m.group(1) };
        m = Pattern.compile("(?i)^(?:na verdade\\s+é|na verdade|o certo\\s+é|o nome\\s+é|considera\\s+s[óo]|considere\\s+s[óo]|considera\\s+apenas|leva em conta\\s+(?:s[óo]|apenas))\\s+(.+)$").matcher(t);
        if (m.find()) return new String[]{ null, m.group(1) };
        return null;
    }

    /** Limpa o VALOR ensinado tirando enfeites do começo ("é pra você considerar
     *  apenas o nome ..." → "..."). Casa no asciiLower (sem acento, tamanho igual)
     *  e corta do original — assim "pra você"/"é" com acento não escapam pelo \b. */
    private static final Pattern ENFEITE_ENSINO = Pattern.compile("(?i)^(nao no caso|nao|e|eh|era|na verdade|o certo|o nome|o produto|a categoria|pra voce|pra vc|pra|para|considerar|considere|considera|apenas|so|somente|a palavra|o termo|que|seria|pode considerar|leva em conta)(?:[\\s,.:]+|$)");
    private String limpaValorEnsino(String v) {
        if (v == null) return null;
        String s = v.trim();
        for (int i = 0; i < 10; i++) {
            Matcher m = ENFEITE_ENSINO.matcher(asciiLower(s));   // mesmo tamanho do original
            if (!m.find()) break;
            s = s.substring(Math.min(m.end(), s.length())).trim();
        }
        return limpaNome(s);
    }

    // ── Promoção — usa `precoOriginal` como "de" e `preco` como promo. Preserva o normal. ──
    private static final Set<String> STOP_PROMO;
    static {
        STOP_PROMO = new HashSet<>(STOP_ACAO);
        STOP_PROMO.addAll(Arrays.asList("promocao", "promocoes", "promo", "promocional", "desconto", "descontos", "oferta", "ofertas", "em"));
    }

    private boolean promoAtiva(Produto p) {
        return p != null && p.getPrecoOriginal() != null && p.getPreco() != null
            && p.getPrecoOriginal().compareTo(p.getPreco()) > 0;
    }

    private Map<String, Object> itemPromo(Produto p, BigDecimal precoPromo, boolean tirar) {
        String atual = promoAtiva(p) ? moeda(p.getPrecoOriginal()) + " → " + moeda(p.getPreco()) + " (promo)" : moeda(p.getPreco());
        String novo = tirar
            ? "volta pro normal " + moeda(promoAtiva(p) ? p.getPrecoOriginal() : p.getPreco())
            : (precoPromo != null ? moeda(p.getPreco()) + " → " + moeda(precoPromo) + " (promo)" : null);
        return item("promoProduto", p.getNome(), p.getFotoUrl(), "tag", atual, novo,
            "coloca o " + p.getNome() + " em promoção por ",
            mp("produtoId", p.getId(), "precoNovo", precoPromo, "tirar", tirar ? "1" : null));
    }

    private Map<String, Object> fluxoPromocao(Restaurante r, String texto, String norm) {
        boolean tirar = norm.matches(".*\\b(tira|tirar|tire|remove|remover|retira\\w*|acaba\\w*|encerra\\w*|cancela\\w*|sem)\\b.*");
        // alvo: depois de "produto X"; senão nome entre a promo e o preço (ambas as ordens).
        String consulta;
        if (norm.matches(".*\\bproduto\\b.*")) {
            consulta = alvoProduto(texto, norm);
        } else {
            String m = fatiaEntre(texto, "(promoc\\w*|desconto|oferta)", "(por\\b|no valor|valor|pra|para)");
            if (m == null || m.isBlank())
                m = fatiaEntre(texto, "(coloca\\w*|poe|poem|bota\\w*|faz|fazer|faca|deixa\\w*|cria\\w*|adiciona\\w*|manda\\w*|da|dar|tira\\w*|remove\\w*)",
                    "(em promoc\\w*|promoc\\w*|desconto|oferta|por\\b|no valor|valor|pra|para)");
            consulta = (m != null && !m.isBlank()) ? MyHelpTexto.norm(m) : alvoConsulta(norm, STOP_PROMO);
        }
        lembrarTermo(r.getId(), consulta);
        consulta = resolverPronome(r.getId(), consulta);
        consulta = resolverApelidoProduto(r.getId(), consulta);
        if (consulta.isBlank())
            return texto("De qual produto é a promoção? Ex.: *\"coloca o X-Bacon em promoção por 24,90\"*.");
        List<Produto> cand = new ArrayList<>();
        Produto p = melhorProduto(r.getId(), consulta, cand);
        BigDecimal precoP = precoMarcado(texto);
        if (p == null) {
            if (cand.isEmpty()) return texto("Não achei *" + consulta + "* no seu cardápio 😕");
            List<Map<String, Object>> ops = new ArrayList<>();
            for (Produto x : cand) ops.add(itemPromo(x, tirar ? null : precoP, tirar));
            return escolha("promoProduto", ops, tirar ? "Tirar promoção de qual?" : "Qual produto entra em promoção?");
        }
        if (tirar) {
            if (!promoAtiva(p)) return texto("O *" + p.getNome() + "* não está em promoção agora 🙂");
            return card("promoProduto", itemPromo(p, null, true), "Confere: tirar a promoção e voltar pro preço normal?");
        }
        if (precoP == null) {
            pendencia.put(r.getId(), mp("acao", "promoProduto", "produtoId", p.getId()));
            return texto("Por quanto fica a promoção do *" + p.getNome() + "*? (hoje " + moeda(p.getPreco()) + ")");
        }
        BigDecimal normal = promoAtiva(p) ? p.getPrecoOriginal() : p.getPreco();
        if (precoP.compareTo(normal) >= 0)
            return texto("A promoção (" + moeda(precoP) + ") precisa ser MENOR que o preço normal (" + moeda(normal) + ") 🙂");
        return card("promoProduto", itemPromo(p, precoP, false), "Confere a promoção — o preço normal fica preservado:");
    }

    // ── Novo produto ─────────────────────────────────────────────────────
    private Map<String, Object> fluxoNovoProduto(Restaurante r, String texto, String norm) {
        // Preço é OPCIONAL e só conta MARCADO ("por 12", "R$ 12", "12 reais").
        // Número colado em unidade ("500g", "2L", "300ml") é TAMANHO, nunca preço.
        BigDecimal preco = precoMarcado(texto);
        // Nome: prefere depois de "chamado/nome"; senão depois de "produto"; senão
        // depois do verbo. Para em QUALQUER marcador (categoria/preço/descrição/chamado),
        // em qualquer ordem — ex.: "produto dentro da categoria X chamado: Y".
        String fimNome = "(por\\b|no valor|custa|categoria|descric\\w*|chamad\\w*|preco\\b|valor\\b)";
        String nome = fatiaEntre(texto, "(chamad\\w*|nome)", fimNome);
        if (nome == null) nome = fatiaEntre(texto, "produto", fimNome);
        if (nome == null) nome = fatiaEntre(texto, "(cria|criar|crie|adiciona|adicione|cadastra|cadastre|novo|nova)", fimNome);
        nome = semSubstantivo(nome, "produto|item|lanche|prato");
        lembrarTermo(r.getId(), nome);                  // pra corrigir depois ("não, era Y")
        nome = apelidoValorOriginal(r.getId(), nome);   // memória: "cria peixe" → "Peixe Frito"
        String descNova = fatiaEntre(texto, "descric\\w*", "(por\\b|no valor|custa|categoria|chamad\\w*)");
        if (nome == null || nome.isBlank())
            return texto("Qual o nome do produto? Ex.: *\"cria o produto 500g de franguinho na categoria Porções\"* — o preço você põe depois, se quiser.");
        lembrarAlvo(r.getId(), nome);   // contexto pra "coloca ele por 29,90"
        String catConsulta = fatiaEntre(texto, "categoria", "(chamad\\w*|nome|por\\b|descric\\w*)");
        Categoria cat = null;
        if (catConsulta != null && !catConsulta.isBlank()) {
            List<Categoria> cc = new ArrayList<>();
            cat = melhorCategoria(r.getId(), MyHelpTexto.norm(catConsulta), cc);
            if (cat == null && !cc.isEmpty()) cat = cc.get(0);
        }
        String precoFmt = preco != null ? moeda(preco) : "preço a definir";
        String resumo = nome + " • " + precoFmt + (descNova != null ? " • \"" + descNova + "\"" : "");
        if (cat == null) {
            List<Categoria> cats = categoriaRepo.findByRestauranteIdOrderByOrdemAsc(r.getId());
            if (cats.isEmpty())
                return texto("Você ainda não tem categorias. Cria uma primeiro (ex.: *\"cria a categoria Porções\"*) e depois o produto.");
            List<Map<String, Object>> ops = new ArrayList<>();
            for (Categoria x : cats) if (ops.size() < 12) ops.add(item("novoProduto", x.getNome(), null, "folder",
                null, resumo, null, mp("textoNovo", nome, "precoNovo", preco, "categoriaId", x.getId(), "textoDesc", descNova)));
            return escolha("novoProduto", ops, "Em qual categoria vai entrar o *" + nome + "*?");
        }
        return card("novoProduto", item("novoProduto", "Novo produto", null, "tag",
            null, resumo + " • " + cat.getNome(), null,
            mp("textoNovo", nome, "precoNovo", preco, "categoriaId", cat.getId(), "textoDesc", descNova)),
            "Vou criar este produto. Confirma?");
    }

    /** Preço só quando MARCADO ("por 12", "R$ 12", "preço 12", "12 reais"). Número
     *  colado em unidade (500g, 2L, 300ml) é TAMANHO — nunca vira preço. null = sem preço. */
    private BigDecimal precoMarcado(String texto) {
        String t = asciiLower(texto);
        String num = "(\\d{1,6}(?:[.,]\\d{1,2})?)";
        String naoUnidade = "(?!\\s*(?:g|kg|gr|mg|ml|l|lt|litro|litros|grama|gramas|un|und|unid|unidade|cm|mm|kg|kilo|quilo))";
        String achado = null;
        Matcher m = Pattern.compile("(?:\\bpor\\b|\\bpreco\\b|\\bvalor\\b|\\bcusta\\b|no valor|r\\$)\\s*(?:de\\s+)?r?\\$?\\s*" + num + naoUnidade).matcher(t);
        if (m.find()) achado = m.group(1);
        if (achado == null) {
            Matcher m2 = Pattern.compile(num + "\\s*(?:reais|real|conto|pila)\\b").matcher(t);
            if (m2.find()) achado = m2.group(1);
        }
        if (achado == null) return null;
        try { return achado.contains(",") ? new BigDecimal(achado.replace(".", "").replace(",", ".")) : new BigDecimal(achado); }
        catch (Exception e) { return null; }
    }

    // ── Novo grupo de complemento (dentro de um produto) ─────────────────
    private Map<String, Object> fluxoNovoGrupo(Restaurante r, String texto, String norm) {
        String nomeGrupo = fatiaEntre(texto, "grupo", "(no|do|na|da|pro|para|produto|em)");
        String prodConsulta = fatiaEntre(texto, "(no|do|na|da|pro|produto|em)", null);
        if (nomeGrupo == null || nomeGrupo.isBlank())
            return texto("Qual o nome do grupo? Ex.: *\"cria um grupo Adicionais no X-Tudo\"*.");
        if (prodConsulta == null || prodConsulta.isBlank())
            return texto("Em qual produto vai esse grupo *" + nomeGrupo + "*? Ex.: *\"cria um grupo " + nomeGrupo + " no X-Tudo\"*.");
        List<Produto> cand = new ArrayList<>();
        Produto p = melhorProduto(r.getId(), MyHelpTexto.norm(prodConsulta), cand);
        if (p == null) {
            if (cand.isEmpty()) return texto("Não achei o produto *" + prodConsulta + "* pra colocar o grupo 😕");
            List<Map<String, Object>> ops = new ArrayList<>();
            for (Produto x : cand) ops.add(item("novoGrupo", x.getNome(), x.getFotoUrl(), "tag",
                null, "Grupo: " + nomeGrupo, null, mp("produtoId", x.getId(), "textoNovo", nomeGrupo)));
            return escolha("novoGrupo", ops, "Em qual produto entra o grupo *" + nomeGrupo + "*?");
        }
        return card("novoGrupo", item("novoGrupo", "Novo grupo em " + p.getNome(), p.getFotoUrl(), "layers",
            null, nomeGrupo, null, mp("produtoId", p.getId(), "textoNovo", nomeGrupo)),
            "Vou criar este grupo de complemento. Confirma?");
    }

    // ── Novo complemento (item) dentro de um grupo ───────────────────────
    private Map<String, Object> fluxoNovoComplemento(Restaurante r, String texto, String norm) {
        BigDecimal preco = extrairPreco(texto);
        // Prefere pegar o nome logo após "complemento/adicional/opção"; senão, após o verbo.
        String nomeItem = fatiaEntre(texto, "(complemento|adicional|opcao|opcional|item)", "(por|no valor|custa|no grupo|do grupo|na grupo|grupo)");
        if (nomeItem == null) nomeItem = fatiaEntre(texto,
            "(adiciona|adicione|adicionar|inclui|inclua|incluir|cria|criar|crie|cadastra|cadastre|novo|nova)",
            "(por|no valor|custa|no grupo|do grupo|na grupo|grupo)");
        nomeItem = semSubstantivo(nomeItem, "complemento|adicional|opcao|opcional|item");
        String grupoConsulta = fatiaEntre(texto, "grupo", "(do|no|da|na|produto|em)");
        if (nomeItem == null || nomeItem.isBlank())
            return texto("Qual complemento você quer adicionar? Ex.: *\"adiciona o complemento Bacon por 3 no grupo Adicionais\"*.");
        if (grupoConsulta == null || grupoConsulta.isBlank())
            return texto("Em qual grupo entra o *" + nomeItem + "*? Ex.: *\"adiciona " + nomeItem + " por " + (preco != null ? moeda(preco) : "3") + " no grupo Adicionais\"*.");
        List<ComplementoGrupo> cand = new ArrayList<>();
        ComplementoGrupo g = melhorGrupo(r.getId(), MyHelpTexto.norm(grupoConsulta), cand);
        BigDecimal precoFinal = preco == null ? BigDecimal.ZERO : preco;
        if (g == null) {
            if (cand.isEmpty()) return texto("Não achei o grupo *" + grupoConsulta + "* nos seus produtos 😕");
            List<Map<String, Object>> ops = new ArrayList<>();
            for (ComplementoGrupo x : cand) ops.add(item("novoComplemento",
                x.getNome() + (x.getProduto() != null ? " (" + x.getProduto().getNome() + ")" : ""), null, "layers",
                null, nomeItem + " • " + moeda(precoFinal), null, mp("grupoId", x.getId(), "textoNovo", nomeItem, "precoNovo", precoFinal)));
            return escolha("novoComplemento", ops, "Em qual grupo entra o *" + nomeItem + "*?");
        }
        return card("novoComplemento", item("novoComplemento",
            "Novo complemento em " + g.getNome(), null, "layers",
            null, nomeItem + " • " + moeda(precoFinal), null,
            mp("grupoId", g.getId(), "textoNovo", nomeItem, "precoNovo", precoFinal)),
            "Vou adicionar este complemento ao grupo. Confirma?");
    }

    // ── Helpers de match / extração das novas ações ──────────────────────
    /** Melhor produto (único e claro) ou null; preenche candOut com os parecidos. */
    private Produto melhorProduto(Long restId, String consulta, List<Produto> candOut) {
        consulta = resolverApelidoProduto(restId, consulta);   // memória: "coca" → "Coca-Cola 350ml"
        List<Produto> produtos = produtoRepo.findByRestauranteId(restId);
        Set<String> q = MyHelpTexto.tokens(consulta);
        List<Pontuado> ranked = new ArrayList<>();
        for (Produto p : produtos) {
            String pn = MyHelpTexto.norm(p.getNome());
            int s = score(consulta, q, pn, MyHelpTexto.tokens(pn));
            if (s > 0) ranked.add(new Pontuado(p, s));
        }
        ranked.sort(Comparator.comparingInt((Pontuado x) -> x.score).reversed());
        for (Pontuado x : ranked) if (x.score >= 40 && candOut.size() < 6) candOut.add(x.p);
        if (!ranked.isEmpty() && ranked.get(0).score >= 70
            && (ranked.size() == 1 || ranked.get(0).score - ranked.get(1).score >= 20)) {
            lembrarAlvo(restId, ranked.get(0).p.getNome());   // contexto: último produto falado
            return ranked.get(0).p;
        }
        return null;
    }

    private Categoria melhorCategoria(Long restId, String consulta, List<Categoria> candOut) {
        Set<String> q = MyHelpTexto.tokens(consulta);
        List<Object[]> sc = new ArrayList<>();
        for (Categoria c : categoriaRepo.findByRestauranteIdOrderByOrdemAsc(restId)) {
            String cn = MyHelpTexto.norm(c.getNome() == null ? "" : c.getNome());
            int s = score(consulta, q, cn, MyHelpTexto.tokens(cn));
            if (s > 0) sc.add(new Object[]{c, s});
        }
        sc.sort((a, b) -> ((Integer) b[1]) - ((Integer) a[1]));
        for (Object[] o : sc) if ((Integer) o[1] >= 40 && candOut.size() < 8) candOut.add((Categoria) o[0]);
        if (!sc.isEmpty() && (Integer) sc.get(0)[1] >= 70
            && (sc.size() == 1 || (Integer) sc.get(0)[1] - (Integer) sc.get(1)[1] >= 20)) return (Categoria) sc.get(0)[0];
        return null;
    }

    private ComplementoGrupo melhorGrupo(Long restId, String consulta, List<ComplementoGrupo> candOut) {
        Set<String> q = MyHelpTexto.tokens(consulta);
        List<Object[]> sc = new ArrayList<>();
        for (Produto p : produtoRepo.findByRestauranteId(restId))
            for (ComplementoGrupo g : grupoRepo.findByProdutoIdOrderByIdAsc(p.getId())) {
                String gn = MyHelpTexto.norm(g.getNome() == null ? "" : g.getNome());
                int s = score(consulta, q, gn, MyHelpTexto.tokens(gn));
                if (s > 0) sc.add(new Object[]{g, s});
            }
        sc.sort((a, b) -> ((Integer) b[1]) - ((Integer) a[1]));
        for (Object[] o : sc) if ((Integer) o[1] >= 40 && candOut.size() < 8) candOut.add((ComplementoGrupo) o[0]);
        if (!sc.isEmpty() && (Integer) sc.get(0)[1] >= 70
            && (sc.size() == 1 || (Integer) sc.get(0)[1] - (Integer) sc.get(1)[1] >= 20)) return (ComplementoGrupo) sc.get(0)[0];
        return null;
    }

    private ComplementoItem melhorComplemento(Long restId, String consulta, List<ComplementoItem> candOut) {
        Set<String> q = MyHelpTexto.tokens(consulta);
        List<Object[]> sc = new ArrayList<>();
        for (Produto p : produtoRepo.findByRestauranteId(restId))
            for (ComplementoGrupo g : grupoRepo.findByProdutoIdOrderByIdAsc(p.getId())) {
                List<ComplementoItem> itens = g.getItens() != null ? g.getItens() : itemRepo.findByGrupoId(g.getId());
                for (ComplementoItem it : itens) {
                    String in = MyHelpTexto.norm(it.getNome() == null ? "" : it.getNome());
                    int s = score(consulta, q, in, MyHelpTexto.tokens(in));
                    if (s > 0) sc.add(new Object[]{it, s});
                }
            }
        sc.sort((a, b) -> ((Integer) b[1]) - ((Integer) a[1]));
        for (Object[] o : sc) if ((Integer) o[1] >= 40 && candOut.size() < 8) candOut.add((ComplementoItem) o[0]);
        if (!sc.isEmpty() && (Integer) sc.get(0)[1] >= 70
            && (sc.size() == 1 || (Integer) sc.get(0)[1] - (Integer) sc.get(1)[1] >= 20)) return (ComplementoItem) sc.get(0)[0];
        return null;
    }

    /** Consulta do alvo: tudo antes de "pra", sem stopwords nem as palavras extra. */
    private String alvoConsulta(String norm, Set<String> extra) {
        String s = norm;
        Matcher md = Pattern.compile("\\b(pra|para|=|:)\\b").matcher(norm);
        if (md.find()) s = norm.substring(0, md.start());
        StringBuilder sb = new StringBuilder();
        for (String tok : s.split(" ")) {
            if (tok.isBlank()) continue;
            if (MyHelpTexto.STOPWORDS.contains(tok)) continue;
            if (extra != null && extra.contains(tok)) continue;
            sb.append(tok).append(' ');
        }
        return sb.toString().trim();
    }

    /** Alvo produto: prefere o nome logo depois de "produto"/"do produto"/"no
     *  produto" (cortando no próximo verbo/marcador); senão o resto da frase. */
    private String alvoProduto(String texto, String norm) {
        String m = fatiaEntre(texto, "produto",
            "(pra|para|=|:|por\\b|com\\b|no valor|valor|preco|descric\\w*|chamad\\w*|coloca|poe|bota|deixa|muda|altera|troca|ajusta|atualiza|sobe|aumenta|abaixa|baixa)");
        if (m != null && !m.isBlank()) return MyHelpTexto.norm(m);
        return alvoConsulta(norm, STOP_ACAO);
    }

    /**
     * Memória (aprendizado da loja): se a consulta é EXATAMENTE um apelido que a
     * loja ensinou (ex.: "coca"), resolve pro valor aprendido ("coca cola 350ml").
     * Só troca em match EXATO da chave — texto explícito ("coca cola 2l") tem
     * mais tokens, não bate a chave, e nunca é sobrescrito (evita conflito).
     * Leitura pura (seguro no responder readOnly).
     */
    private String resolverApelidoProduto(Long restId, String consulta) {
        if (consulta == null || consulta.isBlank() || restId == null) return consulta;
        for (MyHelpMemoria mem : memoria.ativas(restId, "product_ref")) {
            if (consulta.equals(mem.getChaveNorm()) && mem.getValor() != null) {
                log.info("[myHelp-mem] loja={} apelido '{}' -> '{}'", restId, consulta, mem.getValor());
                return MyHelpTexto.norm(mem.getValor());
            }
        }
        return consulta;
    }

    /** Como resolverApelidoProduto, mas devolve o VALOR com a caixa original — pra
     *  usar como NOME de produto novo ("cria peixe" → cria "Peixe Frito"). Só match exato. */
    private String apelidoValorOriginal(Long restId, String nome) {
        if (nome == null || nome.isBlank() || restId == null) return nome;
        String chave = MyHelpTexto.norm(nome);
        for (MyHelpMemoria mem : memoria.ativas(restId, "product_ref")) {
            if (chave.equals(mem.getChaveNorm()) && mem.getValor() != null && !mem.getValor().isBlank())
                return mem.getValor();
        }
        return nome;
    }

    /** Resolve pronome ("coloca ELE por 29,90") pro último produto falado na loja. */
    private String resolverPronome(Long restId, String consulta) {
        if (restId == null) return consulta;
        String c = consulta == null ? "" : consulta.trim();
        if (c.isBlank() || PRONOME.matcher(c).matches()) {
            String u = ultimoAlvo.getIfPresent(restId);
            if (u != null && !u.isBlank()) return u;
        }
        return consulta;
    }

    /** Registra o último produto falado (contexto de conversa, leitura-safe). */
    private void lembrarAlvo(Long restId, String nomeProduto) {
        if (restId != null && nomeProduto != null && !nomeProduto.isBlank())
            ultimoAlvo.put(restId, MyHelpTexto.norm(nomeProduto));
    }

    /** Guarda o termo que o dono mencionou (mesmo sem casar) pra permitir corrigir
     *  depois ("não, era Peixe Frito"). Ignora pronomes. */
    private void lembrarTermo(Long restId, String termo) {
        if (restId == null || termo == null) return;
        String t = MyHelpTexto.norm(termo);
        if (!t.isBlank() && !PRONOME.matcher(t).matches() && !isPronome(t)) ultimoTermo.put(restId, t);
    }

    /** A mensagem atual é a RESPOSTA ao que o myHelp perguntou (memória curta). */
    private Map<String, Object> resolverPendencia(Restaurante r, Map<String, Object> pend, String texto) {
        String acao = pend == null ? null : (String) pend.get("acao");
        if (acao == null) return texto("Beleza! Me diz o que você quer fazer 🙂");
        Long pid = pend.get("produtoId") instanceof Number ? ((Number) pend.get("produtoId")).longValue() : null;
        switch (acao) {
            case "nomeProduto": {
                Produto p = pid == null ? null : produtoRepo.findById(pid).orElse(null);
                if (p == null) return texto("Ops, me perdi do produto. Manda o comando de novo 🙂");
                String novo = limpaNome(texto);
                if (novo == null) return texto("Não peguei o nome. Me diz de novo? 🙂");
                return card("nomeProduto", item("nomeProduto", p.getNome(), p.getFotoUrl(), "tag",
                    p.getNome(), novo, null, mp("produtoId", p.getId(), "textoNovo", novo)), "Confere a troca de nome e confirma:");
            }
            case "descProduto": {
                Produto p = pid == null ? null : produtoRepo.findById(pid).orElse(null);
                if (p == null) return texto("Ops, me perdi do produto. Manda o comando de novo 🙂");
                String desc = limpaNome(texto);
                if (desc == null) return texto("Não peguei a descrição. Me diz de novo? 🙂");
                return card("descProduto", item("descProduto", p.getNome(), p.getFotoUrl(), "tag",
                    descTexto(p.getDescricao()), desc, null, mp("produtoId", p.getId(), "textoNovo", desc)), "Confere a nova descrição e confirma:");
            }
            case "precoProduto": {
                Produto p = pid == null ? null : produtoRepo.findById(pid).orElse(null);
                if (p == null) return texto("Ops, me perdi do produto. Manda o comando de novo 🙂");
                BigDecimal v = extrairPreco(texto);
                if (v == null) return texto("Não peguei o preço. Me diz um número (ex.: 25)? 🙂");
                return card("preco", produtoItem(p, v), "Confere e confirma que eu altero o preço:");
            }
            case "novaCategoria": {
                String nome = limpaNome(texto);
                if (nome == null) return texto("Não peguei o nome da categoria 🙂");
                return card("novaCategoria", item("novaCategoria", "Nova categoria", null, "folder",
                    null, nome, null, mp("textoNovo", nome)), "Vou criar esta categoria nova. Confirma?");
            }
            case "promoProduto": {
                Produto p = pid == null ? null : produtoRepo.findById(pid).orElse(null);
                if (p == null) return texto("Ops, me perdi do produto. Manda o comando de novo 🙂");
                BigDecimal v = precoMarcado(texto);
                if (v == null) v = extrairPreco(texto);
                if (v == null) return texto("Não peguei o preço da promoção. Me diz um número? 🙂");
                BigDecimal normal = promoAtiva(p) ? p.getPrecoOriginal() : p.getPreco();
                if (v.compareTo(normal) >= 0) return texto("A promoção precisa ser MENOR que " + moeda(normal) + " 🙂");
                return card("promoProduto", itemPromo(p, v, false), "Confere a promoção — o preço normal fica preservado:");
            }
            default: return texto("Beleza! Me diz o que você quer fazer 🙂");
        }
    }

    /** Novo texto (nome/descrição) preservando acento/caixa, após "pra/para/:". */
    private String textoNovoAposPra(String textoBruto) {
        if (textoBruto == null) return null;
        Matcher m = Pattern.compile("(?i)\\b(?:pra|para|=|:)\\s+(.+)$").matcher(textoBruto.trim());
        if (!m.find()) return null;
        return limpaNome(m.group(1));
    }

    /** Minúsculas + sem acento MANTENDO o tamanho (1 char → 1 char), pra casar
     *  marcadores acentuados como "descrição" no texto original sem desalinhar índices. */
    private static String asciiLower(String s) {
        s = s.toLowerCase();
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'á': case 'à': case 'â': case 'ã': case 'ä': c = 'a'; break;
                case 'é': case 'è': case 'ê': case 'ë': c = 'e'; break;
                case 'í': case 'ì': case 'î': case 'ï': c = 'i'; break;
                case 'ó': case 'ò': case 'ô': case 'õ': case 'ö': c = 'o'; break;
                case 'ú': case 'ù': case 'û': case 'ü': c = 'u'; break;
                case 'ç': c = 'c'; break;
                case 'ñ': c = 'n'; break;
                default: break;
            }
            b.append(c);
        }
        return b.toString();
    }

    /** Fatia do texto original entre a 1ª ocorrência de `depoisDe` (regex) e `antesDe` (regex). */
    private String fatiaEntre(String orig, String depoisDe, String antesDe) {
        if (orig == null) return null;
        String low = asciiLower(orig);            // mesmo tamanho do original → índices batem
        int from = 0;
        if (depoisDe != null) {
            Matcher m = Pattern.compile("\\b(?:" + depoisDe + ")\\b").matcher(low);
            if (m.find()) from = m.end(); else return null;
        }
        int to = orig.length();
        if (antesDe != null) {
            Matcher m = Pattern.compile("\\b(?:" + antesDe + ")\\b").matcher(low);
            if (m.find(Math.min(from, low.length()))) to = m.start();
        }
        if (from > to) return null;
        return limpaNome(orig.substring(from, to));
    }

    /** Limpa nome capturado: tira artigos/conectores do começo, aspas/pontuação e
     *  preço MARCADO no fim (R$, "por N", "N reais") — mantém número de tamanho
     *  tipo "Açaí 300"/"Coca 2L". Strips de cauda em loop até estabilizar. */
    private String limpaNome(String s) {
        if (s == null) return null;
        s = s.trim().replaceAll("^[\"'“”]+|[\"'“”]+$", "").replaceAll("^[,;:\\-\\s]+|[,;:\\s]+$", "").trim();
        // cortesia no COMEÇO ("pra mim", "por favor", "pra você"...)
        s = s.replaceAll("(?i)^(por favor|por gentileza|pra mim|para mim|pra voce|pra vc|pro senhor|pra senhora|faz favor|faca favor)\\s+", "").trim();
        s = s.replaceAll("(?i)^(o|a|os|as|um|uma|de|do|da|meu|minha|chamad[oa]|com o nome|com|:|-)\\s+", "").trim();
        for (int i = 0; i < 4; i++) {
            String antes = s;
            // pontuação no fim ("?", "!", ".", "…") — tira antes pra soltar a cortesia
            s = s.replaceAll("[.?!…;:,\\s]+$", "").trim();
            // cortesias/enfeites no fim: "por favor", "pra mim", "obrigado"...
            s = s.replaceAll("(?i)[\\s,]+(por\\s+favor|por\\s+gentileza|por\\s+obsequio|pra\\s+mim|para\\s+mim|pra\\s+loja|pra\\s+gente|pra\\s+voce|pra\\s+vc|faz\\s+favor|faca\\s+favor|obrigad[oa]|valeu|vlw|please|pfv|pf)\\s*$", "").trim();
            // preço marcado no fim (R$, "por N", "N reais") — mantém tamanho ("Açaí 300")
            s = s.replaceAll("(?i)\\s+(?:por\\s+)?(?:r\\$?|\\$)\\s*\\d+(?:[.,]\\d+)?\\s*$", "").trim();
            s = s.replaceAll("(?i)\\s+por\\s+\\d+(?:[.,]\\d+)?\\s*(?:reais|real|conto|pila)?\\s*$", "").trim();
            s = s.replaceAll("(?i)\\s+\\d+(?:[.,]\\d+)?\\s*(?:reais|real|conto|pila)\\s*$", "").trim();
            s = s.replaceAll("(?i)\\s+(na|no|da|do|em|de|a|pra|para|com)$", "").trim();
            // enfeites/advérbios soltos no fim ("lá", "aí", "aqui", "então", "agora"...)
            s = s.replaceAll("(?i)\\s+(la|lá|ai|aí|ali|aqui|entao|então|agora|mesmo|então)$", "").trim();
            s = s.replaceAll("[,;:\\s]+$", "").trim();
            if (s.equals(antes)) break;
        }
        return s.isEmpty() ? null : s;
    }

    /** Tira um substantivo colado no começo do nome capturado (ex.: "complemento Bacon" → "Bacon"). */
    private String semSubstantivo(String nome, String substantivos) {
        if (nome == null) return null;
        String s = nome.replaceAll("(?i)^(?:o |a |um |uma )?(?:" + substantivos + ")\\s+", "").trim();
        return s.isEmpty() ? null : s;
    }

    private static String descTexto(String d) { return d == null || d.isBlank() ? "(sem descrição)" : d; }

    /** Monta um payload (pares chave/valor; ignora valores null). */
    private static Map<String, Object> mp(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** Item genérico de card/opção. atualFmt/novoFmt podem ser texto OU moeda. */
    private Map<String, Object> item(String acao, String titulo, String fotoUrl, String icone,
                                     String atualFmt, String novoFmt, String prefixo, Map<String, Object> payload) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("acao", acao);
        c.put("titulo", titulo);
        if (fotoUrl != null) c.put("fotoUrl", fotoUrl);
        if (icone != null) c.put("icone", icone);
        if (atualFmt != null) c.put("atualFmt", atualFmt);
        if (novoFmt != null) c.put("novoFmt", novoFmt);
        if (prefixo != null) c.put("prefixo", prefixo);
        if (payload == null) payload = new LinkedHashMap<>();
        payload.put("acao", acao);
        c.put("payload", payload);
        return c;
    }

    private Map<String, Object> itemComplemento(ComplementoItem it, BigDecimal precoNovo) {
        String prod = it.getGrupo() != null && it.getGrupo().getProduto() != null ? it.getGrupo().getProduto().getNome() : null;
        String grp = it.getGrupo() != null ? it.getGrupo().getNome() : null;
        String titulo = it.getNome() + (grp != null ? " — " + grp : "") + (prod != null ? " (" + prod + ")" : "");
        return item("precoComplemento", titulo, null, "layers",
            moeda(it.getPrecoAdicional()), precoNovo != null ? moeda(precoNovo) : null,
            "muda o preço do complemento " + it.getNome() + " pra ",
            mp("itemId", it.getId(), "precoNovo", precoNovo));
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

    // ── Dispatcher genérico do /confirmar (todas as ações passam por aqui) ──
    @Transactional
    public Map<String, Object> confirmar(Restaurante r, Map<String, Object> body) {
        String acao = cvS(body.get("acao"));
        if (acao == null) acao = "preco";
        switch (acao) {
            case "taxaBairro":       return confirmarTaxaBairro(r, cvS(body.get("bairroNome")), cvD(body.get("taxaNova")));
            case "nomeProduto":      return confirmarTextoProduto(r, cvL(body.get("produtoId")), cvS(body.get("textoNovo")), false);
            case "descProduto":      return confirmarTextoProduto(r, cvL(body.get("produtoId")), cvS(body.get("textoNovo")), true);
            case "nomeCategoria":    return confirmarNomeCategoria(r, cvL(body.get("categoriaId")), cvS(body.get("textoNovo")));
            case "precoComplemento": return confirmarPrecoComplemento(r, cvL(body.get("itemId")), cvD(body.get("precoNovo")));
            case "novaCategoria":    return confirmarNovaCategoria(r, cvS(body.get("textoNovo")));
            case "novoProduto":      return confirmarNovoProduto(r, cvS(body.get("textoNovo")), cvD(body.get("precoNovo")), cvL(body.get("categoriaId")), cvS(body.get("textoDesc")));
            case "novoGrupo":        return confirmarNovoGrupo(r, cvL(body.get("produtoId")), cvS(body.get("textoNovo")));
            case "novoComplemento":  return confirmarNovoComplemento(r, cvL(body.get("grupoId")), cvS(body.get("textoNovo")), cvD(body.get("precoNovo")));
            case "promoProduto":     return confirmarPromo(r, cvL(body.get("produtoId")), cvD(body.get("precoNovo")), "1".equals(cvS(body.get("tirar"))));
            case "aprender":         return confirmarAprender(r, cvS(body.get("gatilho")), cvS(body.get("valor")), cvS(body.get("contexto")));
            default:                 return confirmarPreco(r, cvL(body.get("produtoId")), cvD(body.get("precoNovo")));
        }
    }

    @Transactional
    public Map<String, Object> confirmarTextoProduto(Restaurante r, Long produtoId, String textoNovo, boolean descricao) {
        if (produtoId == null || textoNovo == null || textoNovo.isBlank()) return texto("Faltou o produto ou o texto 🤔");
        Produto p = produtoRepo.findById(produtoId).orElse(null);
        if (p == null || p.getRestaurante() == null || !p.getRestaurante().getId().equals(r.getId()))
            return texto("Não achei esse produto na sua loja 😕");
        String novo = textoNovo.trim();
        if (descricao) {
            p.setDescricao(novo);
        } else {
            if (novo.length() > 120) return texto("Esse nome ficou muito longo. Manda um mais curtinho 🙂");
            p.setNome(novo);
        }
        produtoRepo.save(p);
        invalidarCachePublico(r.getSlug());
        log.info("[myHelp] loja={} {} produto={} -> '{}'", r.getSlug(), descricao ? "descricao" : "nome", p.getId(), novo);
        Map<String, Object> out = texto(descricao
            ? "Pronto! ✅ Descrição do *" + p.getNome() + "* atualizada."
            : "Pronto! ✅ Produto agora se chama *" + novo + "*.");
        out.put("alterado", true);
        return out;
    }

    @Transactional
    public Map<String, Object> confirmarNomeCategoria(Restaurante r, Long categoriaId, String nomeNovo) {
        if (categoriaId == null || nomeNovo == null || nomeNovo.isBlank()) return texto("Faltou a categoria ou o nome 🤔");
        Categoria c = categoriaRepo.findById(categoriaId).orElse(null);
        if (c == null || c.getRestaurante() == null || !c.getRestaurante().getId().equals(r.getId()))
            return texto("Não achei essa categoria na sua loja 😕");
        String antigo = c.getNome();
        c.setNome(nomeNovo.trim());
        categoriaRepo.save(c);
        invalidarCachePublico(r.getSlug());
        log.info("[myHelp] loja={} nome categoria={} '{}' -> '{}'", r.getSlug(), c.getId(), antigo, nomeNovo.trim());
        Map<String, Object> out = texto("Pronto! ✅ Categoria *" + antigo + "* agora é *" + nomeNovo.trim() + "*.");
        out.put("alterado", true);
        return out;
    }

    @Transactional
    public Map<String, Object> confirmarPrecoComplemento(Restaurante r, Long itemId, BigDecimal precoNovo) {
        if (itemId == null || precoNovo == null) return texto("Faltou o complemento ou o preço 🤔");
        if (precoNovo.signum() < 0 || precoNovo.compareTo(new BigDecimal("100000")) > 0) return texto("Esse valor não parece certo 🙂");
        ComplementoItem it = itemRepo.findById(itemId).orElse(null);
        if (it == null || it.getGrupo() == null || it.getGrupo().getProduto() == null
            || it.getGrupo().getProduto().getRestaurante() == null
            || !it.getGrupo().getProduto().getRestaurante().getId().equals(r.getId()))
            return texto("Não achei esse complemento na sua loja 😕");
        BigDecimal antigo = it.getPrecoAdicional();
        it.setPrecoAdicional(precoNovo);
        itemRepo.save(it);
        invalidarCachePublico(r.getSlug());
        log.info("[myHelp] loja={} preco complemento={} {} -> {}", r.getSlug(), it.getId(), antigo, precoNovo);
        Map<String, Object> out = texto("Pronto! ✅ Complemento *" + it.getNome() + "* agora é " + moeda(precoNovo)
            + (antigo != null ? " (era " + moeda(antigo) + ")" : "") + ".");
        out.put("alterado", true);
        return out;
    }

    @Transactional
    public Map<String, Object> confirmarNovaCategoria(Restaurante r, String nome) {
        if (nome == null || nome.isBlank()) return texto("Faltou o nome da categoria 🤔");
        Restaurante rr = restauranteRepo.findById(r.getId()).orElse(r);
        int ordem = 0;
        for (Categoria c : categoriaRepo.findByRestauranteIdOrderByOrdemAsc(rr.getId()))
            if (c.getOrdem() != null && c.getOrdem() >= ordem) ordem = c.getOrdem() + 1;
        Categoria c = new Categoria();
        c.setRestaurante(rr);
        c.setNome(nome.trim());
        c.setOrdem(ordem);
        c.setAtivo(true);
        categoriaRepo.save(c);
        invalidarCachePublico(r.getSlug());
        log.info("[myHelp] loja={} nova categoria '{}'", r.getSlug(), nome.trim());
        Map<String, Object> out = texto("Pronto! ✅ Categoria *" + nome.trim() + "* criada. Já dá pra cadastrar produtos nela.");
        out.put("alterado", true);
        return out;
    }

    @Transactional
    public Map<String, Object> confirmarNovoProduto(Restaurante r, String nome, BigDecimal preco, Long categoriaId, String descricao) {
        if (nome == null || nome.isBlank() || preco == null) return texto("Faltou o nome ou o preço do produto 🤔");
        if (!precoValido(preco)) return texto("Esse preço não parece certo 🙂");
        Restaurante rr = restauranteRepo.findById(r.getId()).orElse(r);
        Produto p = new Produto();
        p.setRestaurante(rr);
        p.setNome(nome.trim());
        p.setPreco(preco);
        p.setDisponivel(true);
        if (descricao != null && !descricao.isBlank()) p.setDescricao(descricao.trim());
        if (categoriaId != null) {
            Categoria cat = categoriaRepo.findById(categoriaId).orElse(null);
            if (cat != null && cat.getRestaurante() != null && cat.getRestaurante().getId().equals(rr.getId()))
                p.setCategoria(cat);
        }
        produtoRepo.save(p);
        invalidarCachePublico(r.getSlug());
        log.info("[myHelp] loja={} novo produto '{}' {} cat={}", r.getSlug(), nome.trim(), preco, categoriaId);
        Map<String, Object> out = texto("Pronto! ✅ Produto *" + nome.trim() + "* criado por " + moeda(preco)
            + (p.getCategoria() != null ? " em *" + p.getCategoria().getNome() + "*" : "") + ".");
        out.put("alterado", true);
        return out;
    }

    @Transactional
    public Map<String, Object> confirmarNovoGrupo(Restaurante r, Long produtoId, String nome) {
        if (produtoId == null || nome == null || nome.isBlank()) return texto("Faltou o produto ou o nome do grupo 🤔");
        Produto p = produtoRepo.findById(produtoId).orElse(null);
        if (p == null || p.getRestaurante() == null || !p.getRestaurante().getId().equals(r.getId()))
            return texto("Não achei esse produto na sua loja 😕");
        ComplementoGrupo g = new ComplementoGrupo();
        g.setProduto(p);
        g.setNome(nome.trim());
        g.setObrigatorio(false);
        g.setMinEscolhas(0);
        g.setMaxEscolhas(1);
        grupoRepo.save(g);
        invalidarCachePublico(r.getSlug());
        log.info("[myHelp] loja={} novo grupo '{}' produto={}", r.getSlug(), nome.trim(), p.getId());
        Map<String, Object> out = texto("Pronto! ✅ Grupo *" + nome.trim() + "* criado no *" + p.getNome()
            + "*. Agora é só adicionar os complementos dele (ex.: *\"adiciona Bacon por 3 no grupo " + nome.trim() + "\"*).");
        out.put("alterado", true);
        return out;
    }

    @Transactional
    public Map<String, Object> confirmarNovoComplemento(Restaurante r, Long grupoId, String nome, BigDecimal preco) {
        if (grupoId == null || nome == null || nome.isBlank()) return texto("Faltou o grupo ou o nome do complemento 🤔");
        BigDecimal p = preco == null ? BigDecimal.ZERO : preco;
        if (p.signum() < 0 || p.compareTo(new BigDecimal("100000")) > 0) return texto("Esse valor não parece certo 🙂");
        ComplementoGrupo g = grupoRepo.findById(grupoId).orElse(null);
        if (g == null || g.getProduto() == null || g.getProduto().getRestaurante() == null
            || !g.getProduto().getRestaurante().getId().equals(r.getId()))
            return texto("Não achei esse grupo na sua loja 😕");
        ComplementoItem it = new ComplementoItem();
        it.setGrupo(g);
        it.setNome(nome.trim());
        it.setPrecoAdicional(p);
        it.setAtivo(true);
        itemRepo.save(it);
        invalidarCachePublico(r.getSlug());
        log.info("[myHelp] loja={} novo complemento '{}' {} grupo={}", r.getSlug(), nome.trim(), p, g.getId());
        Map<String, Object> out = texto("Pronto! ✅ Complemento *" + nome.trim() + "* (" + moeda(p) + ") adicionado ao grupo *" + g.getNome() + "*.");
        out.put("alterado", true);
        return out;
    }

    /**
     * Promoção: usa os campos EXISTENTES do sistema — {@code precoOriginal} (o "de"
     * riscado = preço normal) e {@code preco} (o valor efetivo = promo). NUNCA
     * sobrescreve nem perde o preço normal. Tirar promoção = restaura preco a
     * partir de precoOriginal e zera precoOriginal.
     */
    @Transactional
    public Map<String, Object> confirmarPromo(Restaurante r, Long produtoId, BigDecimal precoPromo, boolean tirar) {
        if (produtoId == null) return texto("Faltou o produto 🤔");
        Produto p = produtoRepo.findById(produtoId).orElse(null);
        if (p == null || p.getRestaurante() == null || !p.getRestaurante().getId().equals(r.getId()))
            return texto("Não achei esse produto na sua loja 😕");
        BigDecimal normal = promoAtiva(p) ? p.getPrecoOriginal() : p.getPreco();
        if (tirar) {
            p.setPreco(normal);
            p.setPrecoOriginal(null);
            produtoRepo.save(p);
            invalidarCachePublico(r.getSlug());
            log.info("[myHelp] loja={} promo removida produto={} normal={}", r.getSlug(), p.getId(), normal);
            Map<String, Object> out = texto("Pronto! ✅ Promoção do *" + p.getNome() + "* removida. Preço normal: " + moeda(normal) + ".");
            out.put("alterado", true);
            return out;
        }
        if (precoPromo == null) return texto("Faltou o preço da promoção 🤔");
        if (precoPromo.signum() <= 0 || precoPromo.compareTo(normal) >= 0)
            return texto("A promoção precisa ser MENOR que o preço normal (" + moeda(normal) + ") 🙂");
        p.setPrecoOriginal(normal);   // preserva o normal no campo "de"
        p.setPreco(precoPromo);       // preço efetivo = promo
        produtoRepo.save(p);
        invalidarCachePublico(r.getSlug());
        log.info("[myHelp] loja={} PROMO produto={} normal={} promo={}", r.getSlug(), p.getId(), normal, precoPromo);
        Map<String, Object> out = texto("Pronto! ✅ *" + p.getNome() + "* em promoção: de " + moeda(normal)
            + " por *" + moeda(precoPromo) + "*. O preço normal (" + moeda(normal) + ") fica preservado.");
        out.put("alterado", true);
        return out;
    }

    /** Confirma o aprendizado de um apelido da loja (grava na memória persistente). */
    @Transactional
    public Map<String, Object> confirmarAprender(Restaurante r, String gatilho, String valor, String contexto) {
        if (gatilho == null || valor == null || gatilho.isBlank() || valor.isBlank())
            return texto("Faltou o que eu devo aprender 🤔");
        String ctx = (contexto == null || contexto.isBlank()) ? "product_ref" : contexto;
        memoria.aprender(r.getId(), ctx, gatilho, valor);
        return texto("Anotado! ✅ Quando você falar *" + gatilho + "* eu vou considerar *" + valor
            + "*. Pode me corrigir depois quando quiser.");
    }

    private static String cvS(Object o) { String s = o == null ? null : o.toString(); return s == null || s.isBlank() ? null : s; }
    private static BigDecimal cvD(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(o.toString().replace(",", ".")); } catch (Exception e) { return null; }
    }
    private static Long cvL(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.valueOf(o.toString().trim()); } catch (Exception e) { return null; }
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
        c.put("prefixo", "muda o preço do " + p.getNome() + " pra ");
        c.put("payload", mp("acao", "preco", "produtoId", p.getId(), "precoNovo", precoNovo));
        return c;
    }
    private Map<String, Object> bairroItem(BairroEntrega b, BigDecimal taxaNova) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("acao", "taxaBairro");
        c.put("bairroNome", b.getNome());
        c.put("titulo", "Bairro: " + b.getNome());
        c.put("atualFmt", moeda(b.getTaxa()));
        if (taxaNova != null) { c.put("taxaNova", taxaNova); c.put("novoFmt", moeda(taxaNova)); }
        c.put("prefixo", "muda a taxa do bairro " + b.getNome() + " pra ");
        c.put("payload", mp("acao", "taxaBairro", "bairroNome", b.getNome(), "taxaNova", taxaNova));
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
