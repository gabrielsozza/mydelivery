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

        // Complementos (grupo / item) — os mais específicos
        if (falaGrupo && criar && !grupoLocal) return fluxoNovoGrupo(r, texto, norm);
        if (criar && (grupoLocal || (falaComplemento && !falaGrupo))) return fluxoNovoComplemento(r, texto, norm);
        if (falaComplemento && (INTENCAO_PRECO.matcher(norm).find() || (verbo && temNum)))
            return fluxoPrecoComplemento(r, texto, norm);
        // Categoria
        if (falaCategoria && criar && !categoriaLocal) return fluxoNovaCategoria(r, texto, norm);
        if (falaCategoria && !categoriaLocal && (falaNome || verbo)) return fluxoNomeCategoria(r, texto, norm);
        // Produto — criar / descrição / nome
        if (criar && (falaProduto || categoriaLocal)) return fluxoNovoProduto(r, texto, norm);
        if (falaDescricao) return fluxoDescricaoProduto(r, texto, norm);
        if (falaNome && !falaCategoria) return fluxoNomeProduto(r, texto, norm);

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
        String nomeNovo = textoNovoAposPra(texto);
        String consulta = alvoConsulta(norm, STOP_ACAO);
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
        if (nomeNovo == null) return texto("Certo, o *" + p.getNome() + "*. Qual vai ser o novo nome?");
        return card("nomeProduto", item("nomeProduto", p.getNome(), p.getFotoUrl(), "tag",
            p.getNome(), nomeNovo, null, mp("produtoId", p.getId(), "textoNovo", nomeNovo)),
            "Confere a troca de nome e confirma:");
    }

    // ── Descrição de produto ─────────────────────────────────────────────
    private Map<String, Object> fluxoDescricaoProduto(Restaurante r, String texto, String norm) {
        String descNova = textoNovoAposPra(texto);
        String consulta = alvoConsulta(norm, STOP_ACAO);
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
        if (descNova == null) return texto("Beleza, o *" + p.getNome() + "*. Qual vai ser a nova descrição?");
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
        String nome = fatiaEntre(texto, "categoria", null);
        if (nome == null || nome.isBlank())
            return texto("Qual o nome da nova categoria? Ex.: *\"cria a categoria Sobremesas\"*.");
        return card("novaCategoria", item("novaCategoria", "Nova categoria", null, "folder",
            null, nome, null, mp("textoNovo", nome)),
            "Vou criar esta categoria nova. Confirma?");
    }

    // ── Novo produto ─────────────────────────────────────────────────────
    private Map<String, Object> fluxoNovoProduto(Restaurante r, String texto, String norm) {
        BigDecimal preco = extrairPreco(texto);
        String nome = fatiaEntre(texto, "produto", "(por|no valor|custa|categoria)");
        if (nome == null) nome = fatiaEntre(texto, "(cria|criar|crie|adiciona|adicione|cadastra|cadastre|novo|nova)", "(por|no valor|custa|categoria)");
        nome = semSubstantivo(nome, "produto|item|lanche|prato");
        if (nome == null || nome.isBlank())
            return texto("Qual o nome do produto? Ex.: *\"cria o produto Coca 2L por 12 na categoria Bebidas\"*.");
        if (preco == null)
            return texto("Beleza, *" + nome + "*. Qual o preço? Ex.: *\"cria o produto " + nome + " por 12 na categoria Bebidas\"*.");
        // Categoria (opcional, mas melhor ter): tenta casar pelo que veio depois de "categoria".
        String catConsulta = fatiaEntre(texto, "categoria", null);
        Categoria cat = null;
        if (catConsulta != null && !catConsulta.isBlank()) {
            List<Categoria> cc = new ArrayList<>();
            cat = melhorCategoria(r.getId(), MyHelpTexto.norm(catConsulta), cc);
            if (cat == null && !cc.isEmpty()) cat = cc.get(0);
        }
        if (cat == null) {
            List<Categoria> cats = categoriaRepo.findByRestauranteIdOrderByOrdemAsc(r.getId());
            if (cats.isEmpty())
                return texto("Você ainda não tem categorias. Cria uma primeiro (ex.: *\"cria a categoria Bebidas\"*) e depois o produto.");
            List<Map<String, Object>> ops = new ArrayList<>();
            for (Categoria x : cats) if (ops.size() < 12) ops.add(item("novoProduto", x.getNome(), null, "folder",
                null, nome + " • " + moeda(preco), null, mp("textoNovo", nome, "precoNovo", preco, "categoriaId", x.getId())));
            return escolha("novoProduto", ops, "Em qual categoria vai entrar o *" + nome + "* (" + moeda(preco) + ")?");
        }
        return card("novoProduto", item("novoProduto", "Novo produto", null, "tag",
            null, nome + " • " + moeda(preco) + " • " + cat.getNome(), null,
            mp("textoNovo", nome, "precoNovo", preco, "categoriaId", cat.getId())),
            "Vou criar este produto. Confirma?");
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
            && (ranked.size() == 1 || ranked.get(0).score - ranked.get(1).score >= 20)) return ranked.get(0).p;
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

    /** Novo texto (nome/descrição) preservando acento/caixa, após "pra/para/:". */
    private String textoNovoAposPra(String textoBruto) {
        if (textoBruto == null) return null;
        Matcher m = Pattern.compile("(?i)\\b(?:pra|para|=|:)\\s+(.+)$").matcher(textoBruto.trim());
        if (!m.find()) return null;
        return limpaNome(m.group(1));
    }

    /** Fatia do texto original entre a 1ª ocorrência de `depoisDe` (regex) e `antesDe` (regex). */
    private String fatiaEntre(String orig, String depoisDe, String antesDe) {
        if (orig == null) return null;
        String low = orig.toLowerCase();
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

    /** Limpa nome capturado: tira artigos/conectores do começo, aspas e um preço no fim. */
    private String limpaNome(String s) {
        if (s == null) return null;
        s = s.trim().replaceAll("^[\"'“”]+|[\"'“”]+$", "").trim();
        s = s.replaceAll("(?i)^(o|a|os|as|um|uma|de|do|da|meu|minha|chamad[oa]|com o nome|:|-)\\s+", "").trim();
        // Remove só PREÇO MARCADO no fim (R$, "por N", "N reais") — mantém número
        // de tamanho tipo "Açaí 300" ou "Coca 2L".
        s = s.replaceAll("(?i)\\s+(?:por\\s+)?(?:r\\$?|\\$)\\s*\\d+(?:[.,]\\d+)?\\s*$", "").trim();
        s = s.replaceAll("(?i)\\s+por\\s+\\d+(?:[.,]\\d+)?\\s*(?:reais|real|conto|pila)?\\s*$", "").trim();
        s = s.replaceAll("(?i)\\s+\\d+(?:[.,]\\d+)?\\s*(?:reais|real|conto|pila)\\s*$", "").trim();
        s = s.replaceAll("(?i)\\s+(na|no|da|do|em|de|a|pra|para)$", "").trim();
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
            case "novoProduto":      return confirmarNovoProduto(r, cvS(body.get("textoNovo")), cvD(body.get("precoNovo")), cvL(body.get("categoriaId")));
            case "novoGrupo":        return confirmarNovoGrupo(r, cvL(body.get("produtoId")), cvS(body.get("textoNovo")));
            case "novoComplemento":  return confirmarNovoComplemento(r, cvL(body.get("grupoId")), cvS(body.get("textoNovo")), cvD(body.get("precoNovo")));
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
    public Map<String, Object> confirmarNovoProduto(Restaurante r, String nome, BigDecimal preco, Long categoriaId) {
        if (nome == null || nome.isBlank() || preco == null) return texto("Faltou o nome ou o preço do produto 🤔");
        if (!precoValido(preco)) return texto("Esse preço não parece certo 🙂");
        Restaurante rr = restauranteRepo.findById(r.getId()).orElse(r);
        Produto p = new Produto();
        p.setRestaurante(rr);
        p.setNome(nome.trim());
        p.setPreco(preco);
        p.setDisponivel(true);
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
