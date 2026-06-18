package com.mydelivery.service.whatsapp;

import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.mydelivery.config.EvolutionProperties;
import com.mydelivery.model.ConfiguracaoRestaurante;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.Restaurante;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.ConfiguracaoRestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bot de atendimento — leve, reativo, anti-spam, sem LLM.
 *
 * Princípios:
 *  - Só responde a quem mandar mensagem primeiro.
 *  - Detecta intenção em qualquer mensagem (taxa, cardápio, regiões, tempo, mínimo…).
 *  - Se nada casar e for a 1ª interação → saudação + menu de opções.
 *  - Se já saudou e nada casar → dica curta com o menu.
 *  - Cliente pede "atendente" → bot fica em silêncio até alguém devolver pelo painel
 *    (ou expirar o timer de fallback em props.silencioMinutos).
 *  - Envia com delay de "digitando…" pra parecer humano.
 *
 * Estado in-memory por número (instanceName + ":" + numero). Reset no restart é OK.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsappBotService {

    private final WhatsappService whatsappService;
    private final ConfiguracaoRestauranteRepository configRepo;
    private final EvolutionProperties props;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WhatsappIncidenteService incidenteService;
    /** Pra consulta de status de pedido por telefone do cliente. Opcional
     *  pra não quebrar testes legados. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.mydelivery.repository.PedidoRepository pedidoRepo;

    /** Delay (ms) de "digitando…" antes da msg aparecer.
     *  Aumentado pra 2000ms a pedido do operador — bot respondendo em ms
     *  é sinal forte pro WhatsApp marcar como spam (shadow ban). 2s
     *  simula tempo humano de digitação curta e protege a conta.
     *  Tradeoff: cliente vê resposta um pouco depois, mas conta segue viva.
     *
     *  ── EVOLUÇÃO Jun/2026 ──
     *  Tempo fixo de 2000ms criava PADRÃO DETECTÁVEL pelo WhatsApp
     *  (constância de timing é fingerprint forte de bot). Agora randomizado
     *  por mensagem entre TYPING_MIN_MS e TYPING_MAX_MS. Resultado: parece
     *  humano variando velocidade de digitação. Resolve quedas recorrentes
     *  em restaurantes de alto volume (alvo principal do shadow ban). */
    private static final int TYPING_MIN_MS = 2000;
    private static final int TYPING_MAX_MS = 4000;
    private static final java.util.concurrent.ThreadLocalRandom RNG =
            java.util.concurrent.ThreadLocalRandom.current();

    /** Sorteia delay variável por chamada — quebra padrão constante que
     *  WhatsApp usa pra detectar bot. */
    private static int randomTypingDelay() {
        return java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(TYPING_MIN_MS, TYPING_MAX_MS + 1);
    }

    /** Cache em memória de ConfiguracaoRestaurante por restauranteId.
     *  Evita query DB no caminho crítico de cada mensagem. TTL curto (60s)
     *  garante que mudanças do dono (taxa, bairros, etc.) propaguem em até
     *  1 min. Sem isso, cada msg fazia 1 SELECT — em cold start ~300ms. */
    private static final long CACHE_CFG_TTL_MS = 60_000L;
    private final java.util.concurrent.ConcurrentHashMap<Long, CfgCache> cfgCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static class CfgCache {
        final ConfiguracaoRestaurante cfg;
        final long expiraEm;
        CfgCache(ConfiguracaoRestaurante c, long ttl) {
            this.cfg = c;
            this.expiraEm = System.currentTimeMillis() + ttl;
        }
    }

    /** Carrega config com cache TTL. Chamado dentro de decidirResposta. */
    private ConfiguracaoRestaurante carregarCfg(Long restauranteId) {
        if (restauranteId == null) return null;
        var cached = cfgCache.get(restauranteId);
        if (cached != null && cached.expiraEm > System.currentTimeMillis()) {
            return cached.cfg;
        }
        ConfiguracaoRestaurante cfg = configRepo.findByRestauranteId(restauranteId).orElse(null);
        cfgCache.put(restauranteId, new CfgCache(cfg, CACHE_CFG_TTL_MS));
        return cfg;
    }

    /** Invalida o cache de config — chamado quando o dono salva alterações
     *  ou quando o cache acumula muito (sanity). Mantém memória controlada. */
    public void invalidarCacheConfig(Long restauranteId) {
        if (restauranteId == null) cfgCache.clear();
        else cfgCache.remove(restauranteId);
    }

    /**
     * Marcador que o cardápio digital envia quando o cliente clica no botão
     * "Confirmar via WhatsApp" após finalizar o pedido. Formato esperado:
     *   [MyDelivery#PEDIDO_142]
     * Match case-insensitive. Captura o número do pedido pra inclusão na
     * resposta automática.
     */
    private static final java.util.regex.Pattern MARCADOR_PEDIDO_RX =
            java.util.regex.Pattern.compile(
                    "\\[\\s*MyDelivery\\s*#\\s*PEDIDO[_\\s]*([0-9]+)\\s*\\]",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Cliente relata em texto livre que fez um pedido, citando o número.
     * Padrões cobertos:
     *   "fiz o pedido #411", "fiz pedido 411", "acabei de fazer o pedido #411",
     *   "pedido número 411", "meu pedido é o 411", "pedido n° 411".
     * Captura grupo 1 = número.
     * Limita 1-6 dígitos pra evitar match em telefones / valores monetários.
     */
    private static final java.util.regex.Pattern PEDIDO_RELATADO_RX =
            java.util.regex.Pattern.compile(
                    "(?:fiz|acabei\\s+de\\s+fazer|finalizei|completei)\\s+(?:o\\s+)?pedido\\s+(?:n[ºo°]?\\s*|numero\\s*|n[uú]mero\\s*|#)?([0-9]{1,6})\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Estado por número (key = instanceName + ":" + numero). */
    private final Map<String, EstadoNumero> estados = new ConcurrentHashMap<>();

    /**
     * Processa mensagem recebida. Se bot está ligado e regras casam, responde.
     * Caso contrário, fica em silêncio.
     */
    public void processar(WhatsappInstance inst, String numero, String texto) {
        processar(inst, numero, texto, null);
    }

    /**
     * Overload com pushName — mantido por compat. O pushName é IGNORADO:
     * o bot mantém tom impessoal porque muitos clientes têm nome de WhatsApp
     * inválido (só ".", emoji, vazio etc.) e ficava saudação estranha.
     */
    public void processar(WhatsappInstance inst, String numero, String texto, String pushName) {
        try {
            processarInterno(inst, numero, texto, pushName);
        } catch (RuntimeException e) {
            // Qualquer falha inesperada vira incidente classificado em vez de virar
            // log enterrado. Sem isso, bot quebrava silenciosamente e ninguém via.
            log.error("[Bot] exception em processar({}, ***): {}", inst.getInstanceName(), e.getMessage(), e);
            if (incidenteService != null) {
                try {
                    incidenteService.abrirSe(inst,
                            com.mydelivery.model.WhatsappIncidente.Tipo.ERRO_INTERNO_BOT,
                            com.mydelivery.model.WhatsappIncidente.Severidade.MEDIA,
                            "processar() lançou: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                            null);
                } catch (Exception ignore) {}
            }
            // Não relança — webhook precisa responder 200 pra Evolution não fazer retry.
        }
    }

    private void processarInterno(WhatsappInstance inst, String numero, String texto, String pushName) {
        // ── Logs em INFO nos pontos de silêncio: antes eram DEBUG, bot calado
        //    sumia silenciosamente do log de produção. Agora aparece no Railway. ──
        if (!Boolean.TRUE.equals(inst.getBotAtivo())) {
            log.info("[Bot:SILENCIO] desligado (botAtivo=false) — instância={}", inst.getInstanceName());
            return;
        }
        if (texto == null || texto.isBlank()) {
            log.info("[Bot:SILENCIO] texto vazio — instância={}", inst.getInstanceName());
            return;
        }

        String numeroLimpo = limparNumero(numero);
        String chave = inst.getInstanceName() + ":" + numeroLimpo;
        EstadoNumero st = estados.computeIfAbsent(chave, k -> new EstadoNumero());

        // (pushName intencionalmente ignorado — muitos clientes usam nome
        //  esquisito tipo "." ou só emoji. Bot mantém tom impessoal.)

        LocalDateTime agora = LocalDateTime.now();

        // Modo silêncio: cliente pediu atendente humano → bot fica quieto.
        // Janela deslizante: cada nova msg do cliente reseta o timer pros próximos N min.
        // Assim, se cliente+atendente ficam X min em silêncio, o bot retoma sozinho.
        if (st.silencioAte != null && agora.isBefore(st.silencioAte)) {
            st.silencioAte = agora.plusMinutes(props.getBot().getSilencioMinutos());
            log.info("[Bot:SILENCIO] modo humano ativo (renovado até {}) — chave={}", st.silencioAte, chave);
            return;
        }

        // Throttle: evita flood se cliente mandar várias mensagens seguidas
        if (st.ultimaResposta != null
                && Duration.between(st.ultimaResposta, agora).toSeconds() < props.getBot().getThrottleSegundos()) {
            log.info("[Bot:SILENCIO] throttle ativo (resta {}s) — chave={}",
                    props.getBot().getThrottleSegundos() - Duration.between(st.ultimaResposta, agora).toSeconds(),
                    chave);
            return;
        }

        String resposta = decidirResposta(inst.getRestaurante(), texto, st, numeroLimpo);
        if (resposta == null) {
            log.info("[Bot:SILENCIO] decidirResposta() retornou null — sem regra casando — texto='{}', instância={}",
                    texto.length() > 60 ? texto.substring(0, 60) + "..." : texto,
                    inst.getInstanceName());
            return;
        }

        // Delay aleatório por chamada: 2000-4000ms. Cada mensagem tem
        // tempo de "digitando" diferente, simulando humano variando velocidade.
        int typingDelay = randomTypingDelay();
        whatsappService.enviarMensagem(inst, numeroLimpo, resposta, typingDelay);
        st.ultimaResposta = agora;
        st.saudou = true;

        // Se ativou modo humano, marca silêncio (timer é fallback caso ninguém devolva pelo painel)
        if (st.pediuAtendente) {
            st.silencioAte = agora.plusMinutes(props.getBot().getSilencioMinutos());
            st.pediuAtendente = false;
            log.info("[Bot] modo humano ativado pra {} (silêncio até {})", chave, st.silencioAte);
        }
    }

    // ── decisão ──

    /** Retorna texto da resposta ou null pra ignorar a mensagem. */
    private String decidirResposta(Restaurante r, String texto, EstadoNumero st, String numeroCliente) {
        String t = normalizar(texto);
        // Cache TTL 60s — economiza ~300ms na primeira msg em cold start
        ConfiguracaoRestaurante cfg = carregarCfg(r.getId());
        st.totalMensagens++;

        // 0. Confirmação automática de pedido — marcador exclusivo do cardápio.
        java.util.regex.Matcher mPed = MARCADOR_PEDIDO_RX.matcher(texto != null ? texto : "");
        if (mPed.find()) {
            String num = mPed.group(1);
            st.ultimaIntencao = "confirma_pedido";
            return saudacaoHorario() + " " + BotVariations.emojiCumprimento()
                    + " Recebemos o seu pedido *#" + num + "* aqui no "
                    + r.getNome() + BotVariations.pontuacaoFim() + "\n\n"
                    + "Ele já está em preparo e vai sair fresquinho pra você. "
                    + "É só aguardar um instantinho que ele bate na sua porta " + BotVariations.emojiMoto()
                    + "\n\n🔗 Acompanhe em tempo real:\n"
                    + "https://mydeliveryfood.com.br/acompanhar.html?id=" + num;
        }

        // 0b. Cliente relata pedido em texto livre — ex: "Acabei de fazer
        // o pedido #411", "fiz pedido 411", "número 411". Detecta intenção
        // e responde com link/status do pedido específico.
        java.util.regex.Matcher mRel = PEDIDO_RELATADO_RX.matcher(texto != null ? texto : "");
        if (mRel.find()) {
            String num = mRel.group(1);
            try {
                Long pedidoId = Long.parseLong(num);
                Pedido p = pedidoRepo == null ? null : pedidoRepo.findById(pedidoId).orElse(null);
                // Só responde como "pedido específico" se o pedido existe
                // E é do MESMO restaurante — evita vazar pedido alheio
                if (p != null && p.getRestaurante() != null
                        && r.getId().equals(p.getRestaurante().getId())) {
                    st.ultimaIntencao = "relata_pedido";
                    return formatarStatus(p);
                }
            } catch (NumberFormatException | NullPointerException ignore) {
                // numero gigante ou inválido → ignora e segue fluxo
            } catch (Exception e) {
                log.warn("[Bot] falha consultando pedido relatado #{}: {}", num, e.getMessage());
            }
        }

        // 1. Atendente humano — PRIORIDADE MÁXIMA
        // Lista ampliada cobrindo "tem alguem", "pra atender", etc.
        // ATENÇÃO de ordem: precisa vir ANTES de regiões (que tinha "atende"
        // genérico e capturava "atender" indevidamente).
        if (contemAlguma(t, "atendente", "humano", "pessoa", "falar com alguem",
                "falar com voce", "atendimento humano", "operador",
                "tem alguem", "tem alguém", "tem alguem ai", "tem alguém aí",
                "alguem pra atender", "alguém pra atender", "pra atender",
                "atender ai", "atender aí", "atende ai", "atende aí",
                "alguem pode", "alguém pode", "tem gente")) {
            st.pediuAtendente = true;
            st.ultimaIntencao = "atendente";
            return "Sem problema! 👤 Vou transferir você pra um atendente humano da " + r.getNome() + ".\n\n"
                    + "Em instantes alguém da equipe responde aqui mesmo. 😊";
        }

        // 2. Regiões / bairros atendidos — FRASES ESPECÍFICAS
        // Antes: "atende"/"atendem" sozinhos casavam com "atender" (humano)
        // e o bot mostrava regiões em vez de transferir. Removidos.
        if (contemAlguma(t, "regiao", "regioes", "região", "regiões",
                "atendem aqui", "atendem ai", "atendem aí",
                "atende aqui", "atende ai", "atende aí",
                "atende em", "atende na", "atende no", "atendem em", "atendem na", "atendem no",
                "entrega aqui", "entrega ai", "entrega aí", "entrega em", "entrega na", "entrega no",
                "entregam aqui", "entregam em", "entregam na", "entregam no",
                "voces entregam", "vocês entregam", "vcs entregam",
                "voces atendem", "vocês atendem", "vcs atendem",
                "bairro", "bairros", "atendido", "cobertura", "qual area", "qual área")) {
            st.ultimaIntencao = "regioes";
            return montarRespostaRegioes(r, t);
        }

        // 3. Taxa de entrega
        if (contemAlguma(t, "taxa", "frete", "valor da entrega", "quanto a entrega", "custo de entrega")) {
            st.ultimaIntencao = "taxa";
            return montarRespostaTaxa(r);
        }

        // 4. Pedido mínimo
        if (contemAlguma(t, "minimo", "pedido min", "valor minimo", "quanto preciso pedir")) {
            st.ultimaIntencao = "minimo";
            return montarRespostaMinimo(r);
        }

        // 5. Tempo de entrega
        if (contemAlguma(t, "demora", "quanto tempo", "tempo de entrega", "leva quanto",
                "tempo medio", "demorar", "rapido")) {
            st.ultimaIntencao = "tempo";
            return montarRespostaTempo(r);
        }

        // 5b. Retirada no balcão — PRECISA VIR ANTES DO CARDÁPIO porque a
        // frase tipo "consigo pedir e retirar?" tem "pedir" (que casaria
        // com cardápio) E "retirar". A intenção real é PERGUNTAR sobre
        // retirada, então essa regra ganha prioridade.
        if (contemAlguma(t, "retirada", "retirar", "buscar ai", "buscar aí",
                "vou buscar", "vou pegar", "vou retirar",
                "posso buscar", "posso pegar", "posso retirar",
                "tem retirada", "trabalham com retirada", "faz retirada",
                "faz takeaway", "takeaway", "take away",
                "ir ate ai", "ir até aí", "ir buscar", "ir retirar",
                "pegar ai", "pegar aí", "pegar no local",
                "passar ai", "passar aí", "passar pra pegar", "passar pra buscar",
                "pedir e retirar", "pedir e buscar", "pedir e pegar")) {
            st.ultimaIntencao = "retirada";
            return montarRespostaRetirada(r);
        }

        // 6. Cardápio / quero pedir / "por onde faço o pedido"
        if (contemAlguma(t, "cardapio", "cardápio", "menu", "produtos", "comprar",
                "pedir", "fazer pedido", "fazer um pedido", "quero pedir", "vou pedir",
                "to pedindo", "tô pedindo", "por onde", "onde faco", "onde faço",
                "como faco", "como faço", "como peco", "como peço",
                "onde peco", "onde peço", "como funciona o pedido",
                "como pedir", "onde pedir", "fazer o pedido", "fazer meu pedido",
                "quero fazer", "vou fazer pedido")) {
            st.ultimaIntencao = "cardapio";
            String link = montarLinkCardapio(r);
            if (!Boolean.TRUE.equals(r.getAberto())) {
                return "A loja está *fechada* no momento 😅\n\n"
                        + "Mas você ainda pode dar uma olhada no cardápio 👉 " + link
                        + "\n\n" + montarLinhaHorarioHoje(r);
            }
            return BotVariations.cardapioAqui() + " 👉 " + link
                    + "\n\nÉ só escolher os itens e finalizar pelo site " + BotVariations.emojiComida();
        }

        // 6e. "Consegue me ajudar?" / "Quais opções?" / "Como funciona?"
        // Cliente quer saber o que o bot pode fazer — mostra o menu.
        if (contemAlguma(t, "consegue me ajudar", "voce consegue", "você consegue",
                "voce pode me ajudar", "você pode me ajudar", "pode me ajudar",
                "consegue ajudar", "me ajudar", "me ajuda",
                "quais opcoes", "quais opções", "com quais", "quais sao",
                "o que voce faz", "o que vc faz", "o que você faz",
                "como funciona", "como funciona o bot", "o que voce pode",
                "o que pode fazer", "o que vc pode", "o que sabe")) {
            st.ultimaIntencao = "como_ajuda";
            return montarMenuCurto(r);
        }

        // 6a. Formas de pagamento aceitas
        if (contemAlguma(t, "pagamento", "paga como", "como pago", "aceita pix",
                "aceita cartao", "aceita cartão", "dinheiro", "cartao credito",
                "cartao debito", "como funciona o pagamento", "forma de pagamento",
                "formas de pagamento", "vcs aceitam", "voces aceitam")) {
            st.ultimaIntencao = "pagamento";
            return montarRespostaPagamento(r, cfg);
        }

        // 6b. Status do pedido — cliente perguntando "cadê meu pedido"
        // Tentamos PRIMEIRO buscar no DB por telefone do remetente. Se achar,
        // respondemos status direto sem precisar de atendente. Se não acha,
        // cai no fallback de transferir pra equipe.
        if (contemAlguma(t, "cade meu pedido", "cadê meu pedido", "meu pedido",
                "ja saiu", "já saiu", "status pedido", "ta demorando",
                "tá demorando", "to esperando", "tô esperando", "demora muito")) {
            st.ultimaIntencao = "status";
            String respStatus = tentarResponderStatusPedido(r, numeroCliente);
            if (respStatus != null) return respStatus;
            // Sem pedido recente — fallback original
            return saudacaoHorario() + " " + BotVariations.emojiMoto() + "\n\n"
                    + "Não encontrei nenhum pedido recente pelo seu número aqui no "
                    + r.getNome() + ".\n\n"
                    + "Se você fez pelo balcão ou pelo telefone, posso te transferir pra equipe — digite *atendente*.";
        }

        // 6c. Preço de um produto específico — direciona pro cardápio
        if (contemAlguma(t, "quanto custa", "qual o preco", "qual o preço",
                "preço de", "preco de", "valor do", "valor da")) {
            st.ultimaIntencao = "preco_produto";
            String link = montarLinkCardapio(r);
            return "Os preços estão todos atualizadinhos no cardápio 👉 " + link
                    + "\n\nLá você vê o valor exato e ainda já pode montar seu pedido. 😊";
        }

        // 6d. Reclamação / problema — escala com empatia
        if (contemAlguma(t, "reclamacao", "reclamação", "problema", "veio errado",
                "veio frio", "veio gelado", "atrasou", "atrasada", "atrasado",
                "esqueceram", "faltando", "faltou")) {
            st.pediuAtendente = true;
            st.ultimaIntencao = "reclamacao";
            return "Eita, sinto muito 😞\n\n"
                    + "Vou te conectar agora mesmo com a equipe do "
                    + r.getNome() + " pra resolver. Só um instante.";
        }

        // 7. Horário / aberto / fechado / funcionando
        if (contemAlguma(t, "horario", "horário", "que horas", "abre", "aberto", "aberta",
                "abertos", "abertas", "ta aberto", "ta aberta", "esta aberto", "esta aberta",
                "estao abertos", "estao abertas", "funcionamento", "funcionando", "funciona",
                "atendendo", "ainda atende", "ta atendendo", "fechado", "fechada", "fechados",
                "ta funcionando", "esta funcionando", "vcs estao", "voces estao")) {
            st.ultimaIntencao = "horario";
            return montarRespostaHorario(r);
        }

        // 7a. Endereço / localização
        if (contemAlguma(t, "endereco", "onde fica", "onde voces ficam", "onde estao",
                "localizacao", "localização", "como chegar", "qual o endereco",
                "qual endereco", "lugar", "endereço")) {
            st.ultimaIntencao = "endereco";
            return montarRespostaEndereco(r);
        }

        // 7b. Telefone / contato
        if (contemAlguma(t, "telefone", "numero", "contato", "telefonar",
                "ligar", "qual o telefone", "fone", "whats")) {
            st.ultimaIntencao = "telefone";
            return montarRespostaTelefone(r);
        }

        // 7c. CNPJ / CPF — política: bot não revela
        if (contemAlguma(t, "cnpj", "cpf", "documento", "razao social", "razão social")) {
            st.ultimaIntencao = "documento";
            return "Essa informação não está disponível no atendimento automático. "
                    + "Se precisar do dado fiscal pra nota, fale com a equipe — digite *atendente*.";
        }

        // 8. Agradecimento curto
        if (contemAlguma(t, "obrigado", "obrigada", "valeu", "vlw", "thanks",
                "agradeço", "agradecido", "muito obrigado", "muito obrigada")) {
            st.ultimaIntencao = "agradecimento";
            return BotVariations.porNada() + BotVariations.pontuacaoFim() + " " + BotVariations.emojiObrigado()
                    + " " + BotVariations.qualquerDuvida() + ".";
        }

        // 8a. Confirmação curta ("sim", "ok", "blz") sem contexto
        if (t.length() <= 12 && contemAlguma(t, "sim", "ok", "perfeito", "show",
                "beleza", "blz", "blzz", "otimo", "ótimo", "legal", "bacana", "uhum")) {
            st.ultimaIntencao = "confirmacao";
            return BotVariations.combinado() + BotVariations.pontuacaoFim() + " "
                    + BotVariations.emojiOk() + " " + BotVariations.qualquerDuvida() + ".";
        }

        // 9. Saudação / 1ª interação → apresenta o bot
        boolean ehSaudacao = contemAlguma(t, "oi", "ola", "ola ", "opa", "bom dia",
                "boa tarde", "boa noite", "hey", "eai", "e ai", "tudo bem", "td bem", "salve");
        if (!st.saudou || ehSaudacao) {
            st.ultimaIntencao = "saudacao";
            return montarApresentacao(r, st);
        }

        // 10. Anti-loop: 8 msgs sem casamento → sugere atendente
        // Aumentado de 5 → 8 porque estava sugerindo humano cedo demais
        // (depois de 4-5 mensagens normais o cliente já caia nessa msg).
        if (st.totalMensagens >= 8 && !"sugeriu_humano".equals(st.ultimaIntencao)) {
            st.ultimaIntencao = "sugeriu_humano";
            return "Hmm, parece que não consegui te ajudar direito 😅\n\n"
                    + "Que tal eu te transferir pra alguém da equipe? "
                    + "Digite *atendente* que já te coloco em contato com a equipe do "
                    + r.getNome() + ".";
        }

        // 11. Fallback: menu curto
        st.ultimaIntencao = "menu";
        return montarMenuCurto(r);
    }

    // ── Helpers de personalização ──

    /** Saudação contextual pelo horário — agora delega pro pool de variações
     *  do {@link BotVariations}. Resposta varia entre 4-5 opções por período
     *  do dia (anti-fingerprint). */
    private String saudacaoHorario() {
        return BotVariations.saudacao();
    }

    /**
     * Responde sobre retirada baseado em {@code restaurante.getModos()}.
     * Se "retirada" está na lista → confirma e manda pro cardápio.
     * Se NÃO está → avisa que só faz delivery (sem fechar conversa).
     *
     * Resposta usa variações de saudação/emoji pra reduzir padrão.
     */
    private String montarRespostaRetirada(Restaurante r) {
        boolean aceitaRetirada = false;
        try {
            java.util.List<String> modos = r.getModos();
            if (modos != null) {
                for (String m : modos) {
                    if (m != null && m.equalsIgnoreCase("retirada")) {
                        aceitaRetirada = true;
                        break;
                    }
                }
            }
        } catch (Exception ignored) { /* fail-safe */ }

        String link = montarLinkCardapio(r);

        if (aceitaRetirada) {
            StringBuilder sb = new StringBuilder();
            sb.append("Sim, ").append(r.getNome()).append(" aceita retirada no balcão ")
                    .append(BotVariations.emojiOk()).append("\n\n");
            sb.append("É só fazer o pedido pelo cardápio 👉 ").append(link).append("\n\n");
            sb.append("Na hora de fechar, escolhe a opção *Retirar no local* e a gente avisa quando estiver pronto " + BotVariations.emojiComida());
            return sb.toString();
        }

        // Não aceita retirada
        StringBuilder sb = new StringBuilder();
        sb.append("Por enquanto o ").append(r.getNome()).append(" trabalha *só com delivery*, sem retirada no balcão ")
                .append(BotVariations.emojiMoto()).append("\n\n");
        sb.append("Mas você pode pedir tranquilo pelo cardápio que entregamos aí 👉 ").append(link);
        return sb.toString();
    }

    /**
     * Throttle in-memory de notificações proativas: evita mandar mais de
     * 1 msg pro mesmo número em <5min. Chave = telefone limpo.
     */
    private final Map<String, java.time.LocalDateTime> ultimaNotificacaoPorNumero =
            new ConcurrentHashMap<>();
    private static final int THROTTLE_NOTIFICACAO_MIN = 5;

    /**
     * Envia notificação proativa com link de acompanhamento APÓS pedido criado.
     * Chamado pelo PedidoService logo após salvar o pedido novo.
     *
     * ── SALVAGUARDAS ANTI-SHADOWBAN ──
     *  1. Async (não bloqueia criação do pedido).
     *  2. Delay aleatório 15-90s antes de enviar.
     *  3. Mensagem composta de 5 pools de variações (milhares de combinações).
     *  4. Só horário comercial 8h-23h.
     *  5. Throttle: 1 msg / número / 5min.
     *  6. Só DELIVERY ou RETIRADA.
     *  7. Fail-safe completo: NUNCA quebra criação do pedido.
     *  8. Toggle no restaurante.
     */
    @org.springframework.scheduling.annotation.Async
    public void notificarLinkAcompanhamentoAsync(
            Restaurante restaurante,
            Long pedidoId,
            String tipo,
            String telefoneCliente) {
        try {
            if (restaurante == null
                    || !Boolean.TRUE.equals(restaurante.getNotificarLinkAcompanhamentoWhatsapp())) {
                return;
            }
            if (tipo == null || !(tipo.equalsIgnoreCase("DELIVERY") || tipo.equalsIgnoreCase("RETIRADA"))) {
                return;
            }
            if (telefoneCliente == null || telefoneCliente.isBlank()) return;
            String numero = limparNumero(telefoneCliente);
            if (numero.length() < 10) return;
            if (!BotVariations.dentroHorarioNotificacao()) {
                log.info("[Bot:Notif] fora do horário comercial — descartando pedido#{}", pedidoId);
                return;
            }
            WhatsappInstance inst = whatsappService.buscar(restaurante);
            if (inst == null
                    || inst.getStatus() != WhatsappInstance.Status.CONECTADA
                    || !Boolean.TRUE.equals(inst.getBotAtivo())) {
                log.info("[Bot:Notif] WA não conectado/ativo — pulando pedido#{}", pedidoId);
                return;
            }
            java.time.LocalDateTime agora = java.time.LocalDateTime.now();
            java.time.LocalDateTime ultima = ultimaNotificacaoPorNumero.get(numero);
            if (ultima != null
                    && java.time.Duration.between(ultima, agora).toMinutes() < THROTTLE_NOTIFICACAO_MIN) {
                log.info("[Bot:Notif] throttle ativo pra {} — pulando pedido#{}", numero, pedidoId);
                return;
            }

            // Delay aleatório 15-90s — simula humano vendo pedido e indo avisar
            int delayMs = BotVariations.randomNotificacaoDelayMs();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }

            // Reconfere horário após o sleep
            if (!BotVariations.dentroHorarioNotificacao()) return;

            String link = "https://mydeliveryfood.com.br/acompanhar.html?id=" + pedidoId;
            String msg = BotVariations.montarMensagemAcompanhamento(pedidoId, link);
            whatsappService.enviarMensagem(inst, numero, msg, randomTypingDelay());
            ultimaNotificacaoPorNumero.put(numero, java.time.LocalDateTime.now());

            log.info("[Bot:Notif] link de acompanhamento enviado — pedido#{}, rest={}, tel={}***, delay={}s",
                    pedidoId, restaurante.getId(),
                    numero.length() > 5 ? numero.substring(0, 5) : numero,
                    delayMs / 1000);
        } catch (Exception e) {
            // CRÍTICO: nunca propagar — criação do pedido NÃO PODE depender disso.
            log.warn("[Bot:Notif] falha enviando link — pedido#{}: {}", pedidoId, e.getMessage());
        }
    }

    /**
     * Tenta resolver "cadê meu pedido" consultando o banco. Se cliente
     * fez pedido nas últimas 24h pelo telefone dele, devolve status atual
     * formatado. Senão, retorna null pra o caller cair no fallback de
     * transferir pra atendente.
     */
    private String tentarResponderStatusPedido(Restaurante r, String numeroCliente) {
        if (pedidoRepo == null || numeroCliente == null || numeroCliente.isBlank()) {
            return null;
        }
        try {
            // Normaliza pra ter chance de bater (cadastro pode ter telefone
            // com ou sem máscara). Tenta o número limpo direto + variação
            // com o "9" do celular brasileiro caso o telefone do cadastro
            // venha em formato curto.
            java.util.List<Pedido> pedidos = pedidoRepo.findUltimosDoTelefone(
                    r.getId(), numeroCliente,
                    java.time.LocalDateTime.now().minusHours(24));
            if (pedidos == null || pedidos.isEmpty()) {
                // Tenta variação SEM código do país (55)
                if (numeroCliente.startsWith("55") && numeroCliente.length() > 4) {
                    String semCodPais = numeroCliente.substring(2);
                    pedidos = pedidoRepo.findUltimosDoTelefone(r.getId(), semCodPais,
                            java.time.LocalDateTime.now().minusHours(24));
                }
            }
            if (pedidos == null || pedidos.isEmpty()) return null;

            Pedido p = pedidos.get(0); // mais recente
            return formatarStatus(p);
        } catch (Exception e) {
            log.warn("[Bot] falha consultando status pelo telefone: {}", e.getMessage());
            return null;
        }
    }

    /** Formata status do pedido em mensagem amigável. */
    private String formatarStatus(Pedido p) {
        String horaPedido = p.getCriadoEm() == null ? ""
                : p.getCriadoEm().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        StringBuilder sb = new StringBuilder();
        sb.append("Achei seu pedido aqui ").append(BotVariations.emojiOk()).append("\n\n");
        sb.append("*Pedido #").append(p.getId()).append("*");
        if (!horaPedido.isEmpty()) sb.append(" — feito às ").append(horaPedido);
        sb.append("\n\n");

        switch (p.getStatus()) {
            case AGUARDANDO_PAGAMENTO -> sb.append("⏳ Aguardando confirmação do pagamento.\n\n")
                    .append("Assim que cair, o pedido vai pra cozinha automaticamente.");
            case PENDENTE -> sb.append("📥 Pedido recebido e na fila de confirmação.\n\n")
                    .append("Em alguns minutos a cozinha começa.");
            case CONFIRMADO -> sb.append("✅ Pedido confirmado e indo pra cozinha.\n\n")
                    .append("Tempo estimado começa a contar agora.");
            case EM_PREPARO -> sb.append("👨‍🍳 Seu pedido está sendo preparado agora mesmo.\n\n")
                    .append("Logo mais sai pra entrega ").append(BotVariations.emojiMoto());
            case PRONTO -> sb.append("🎯 Pedido pronto!\n\n")
                    .append("Vai sair pra entrega em instantes ").append(BotVariations.emojiMoto());
            case SAIU_ENTREGA -> sb.append("🛵 Já saiu pra entrega!\n\n")
                    .append("O entregador deve chegar em breve. Fica de olho no WhatsApp dele se ligar.");
            case ENTREGUE -> sb.append("✅ Pedido entregue.\n\n")
                    .append("Espero que tenha gostado! ").append(BotVariations.emojiObrigado());
            case CANCELADO -> sb.append("❌ Pedido cancelado.\n\n")
                    .append("Se foi engano, é só fazer outro pelo cardápio.");
            case NA_MESA -> sb.append("🍽️ Pedido na mesa.\n\n")
                    .append("Aproveite!");
            default -> sb.append("Status: ").append(p.getStatus().name());
        }

        // Link de acompanhamento (não inclui em status finais onde o link
        // perde o sentido — pedido já cancelado, entregue ou na mesa)
        if (p.getStatus() != Pedido.Status.ENTREGUE
                && p.getStatus() != Pedido.Status.CANCELADO
                && p.getStatus() != Pedido.Status.NA_MESA) {
            sb.append("\n\n🔗 Acompanhe em tempo real:\n")
              .append("https://mydeliveryfood.com.br/acompanhar.html?id=")
              .append(p.getId());
        }
        return sb.toString();
    }

    /** Montagem da resposta de formas de pagamento. Lê ConfiguracaoRestaurante
     *  pra saber se aceita PIX antecipado, cartão online, dinheiro etc.
     *  Quando configuração não disponível, dá resposta genérica e segura. */
    private String montarRespostaPagamento(Restaurante r, ConfiguracaoRestaurante cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("Formas de pagamento aceitas no ").append(r.getNome()).append(" 💳\n\n");
        sb.append("• Dinheiro (na entrega/retirada)\n");
        sb.append("• PIX\n");
        sb.append("• Cartão de crédito ou débito\n");
        if (Boolean.TRUE.equals(r.getExigirPixAntecipado())) {
            sb.append("\n📌 Pra pedidos pelo cardápio digital, o pagamento por PIX é feito *antes* — "
                    + "é só seguir as instruções na hora do checkout.");
        } else {
            sb.append("\nVocê escolhe na hora de fechar o pedido pelo cardápio 👉 ").append(montarLinkCardapio(r));
        }
        return sb.toString();
    }

    // ── builders de resposta ──

    /** Se o restaurante tem logoUrl, prefixa com "IMG::<url>::" pra o
     *  WhatsappService enviar como imagem com legenda em vez de texto puro.
     *  Resolve o "quadrado preto" do preview do link no cliente WhatsApp. */
    private String comLogoSeHouver(Restaurante r, String texto) {
        String logo = r.getLogoUrl();
        if (logo != null && !logo.isBlank()) {
            return "IMG::" + logo + "::" + texto;
        }
        return texto;
    }

    private String montarApresentacao(Restaurante r) {
        return montarApresentacao(r, null);
    }

    /** Overload com EstadoNumero (compat) — usa saudação contextual por horário. */
    private String montarApresentacao(Restaurante r, EstadoNumero st) {
        String link = montarLinkCardapio(r);
        boolean aberto = Boolean.TRUE.equals(r.getAberto());

        // Saudação contextual: só horário do dia, sem nome próprio.
        String sauda = saudacaoHorario() + "!";

        StringBuilder sb = new StringBuilder();
        sb.append(sauda).append(" 👋 Aqui é da *").append(r.getNome()).append("*.\n\n");

        // Se loja fechada, sinaliza ANTES do menu — não induz compra.
        if (!aberto) {
            sb.append("⚠️ No momento estamos *fechados*.\n");
            String linhaH = montarLinhaHorarioHoje(r);
            if (notBlank(linhaH)) sb.append(linhaH).append("\n");
            sb.append("\nVocê ainda pode tirar dúvidas comigo ou dar uma olhada no cardápio:\n");
        } else {
            sb.append("Como posso te ajudar hoje?\n\n");
        }

        sb.append("• 🍽️ *Cardápio*\n");
        sb.append("• 🛵 *Taxa de entrega*\n");
        sb.append("• 📍 *Endereço da loja* / *Regiões atendidas*\n");
        sb.append("• 📞 *Telefone*\n");
        sb.append("• ⏱️ *Tempo de entrega*\n");
        sb.append("• 💰 *Pedido mínimo*\n");
        sb.append("• 🕒 *Horário de funcionamento*\n");
        sb.append("• 👤 *Falar com atendente*\n\n");

        if (aberto) {
            sb.append("Ou já faça seu pedido pelo cardápio 👉 ").append(link);
        } else {
            sb.append("Cardápio para visualização: ").append(link);
        }
        return comLogoSeHouver(r, sb.toString());
    }

    /**
     * Fallback amigável quando a msg do cliente não casou com nenhuma intent.
     * Lista as opções principais — sempre cordial, nunca culpa o cliente.
     */
    private String montarMenuCurto(Restaurante r) {
        boolean aberto = Boolean.TRUE.equals(r.getAberto());
        StringBuilder sb = new StringBuilder("Desculpe, não consegui entender. 😅\n\n");
        if (!aberto) sb.append("⚠️ Aviso: estamos *fechados* no momento.\n\n");
        sb.append("Posso te ajudar com:\n")
          .append("• 🍽️ *Cardápio*\n")
          .append("• 🕒 *Horário* de funcionamento\n")
          .append("• 📍 *Endereço* da loja\n")
          .append("• 🛵 *Taxa de entrega*\n")
          .append("• ⏱️ *Tempo* de entrega\n")
          .append("• 💰 *Pedido mínimo*\n")
          .append("• 👤 *Falar com atendente*\n\n")
          .append(aberto ? "Cardápio: " : "Cardápio (visualização): ")
          .append(montarLinkCardapio(r));
        return comLogoSeHouver(r, sb.toString());
    }

    private String montarLinkCardapio(Restaurante r) {
        return "https://mydeliveryfood.com.br/" + r.getSlug();
    }

    private String montarRespostaHorario(Restaurante r) {
        String horarioHoje = montarLinhaHorarioHoje(r);
        boolean aberto = Boolean.TRUE.equals(r.getAberto());

        StringBuilder sb = new StringBuilder();
        if (aberto) {
            sb.append("🟢 Estamos *abertos* agora!");
        } else {
            sb.append("🌙 No momento estamos *fechados*.");
        }
        if (notBlank(horarioHoje)) {
            sb.append("\n\n").append(horarioHoje);
        }
        sb.append("\n\nCardápio: ").append(montarLinkCardapio(r));
        return sb.toString();
    }

    /**
     * Lê horariosJson e devolve "Funcionamos hoje das 18h às 23h" ou similar.
     * Se hoje for "fechado", indica e tenta sugerir o próximo dia aberto.
     * Retorna string vazia se não tiver nada cadastrado.
     */
    private String montarLinhaHorarioHoje(Restaurante r) {
        if (!notBlank(r.getHorariosJson())) return "";
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> all =
                    new com.fasterxml.jackson.databind.ObjectMapper().readValue(r.getHorariosJson(), java.util.Map.class);
            String[] dias = {"seg","ter","qua","qui","sex","sab","dom"};
            String[] nomes = {"segunda","terça","quarta","quinta","sexta","sábado","domingo"};
            int idxHoje = java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"))
                    .getDayOfWeek().getValue() - 1; // 0=seg ... 6=dom

            Object dia = all.get(dias[idxHoje]);
            if (dia instanceof java.util.Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> mm = (java.util.Map<String, Object>) m;
                if (Boolean.FALSE.equals(mm.get("aberto"))) {
                    // Fechado hoje — procura o próximo dia aberto
                    for (int i = 1; i <= 7; i++) {
                        int idx = (idxHoje + i) % 7;
                        Object d2 = all.get(dias[idx]);
                        if (d2 instanceof java.util.Map<?, ?> m2) {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> mm2 = (java.util.Map<String, Object>) m2;
                            if (!Boolean.FALSE.equals(mm2.get("aberto"))) {
                                String ab2 = formatarHora(strOf(mm2.get("abertura")));
                                String fc2 = formatarHora(strOf(mm2.get("fechamento")));
                                if (notBlank(ab2) && notBlank(fc2)) {
                                    return "Hoje estamos fechados. Próxima " + nomes[idx]
                                            + " funcionamos das *" + ab2 + "* às *" + fc2 + "*.";
                                }
                            }
                        }
                    }
                    return "Hoje estamos fechados.";
                }
                String ab = formatarHora(strOf(mm.get("abertura")));
                String fc = formatarHora(strOf(mm.get("fechamento")));
                if (notBlank(ab) && notBlank(fc)) {
                    return "Funcionamos hoje das *" + ab + "* às *" + fc + "*.";
                }
            }
        } catch (Exception ignore) {}
        return "";
    }

    private static String strOf(Object o) { return o == null ? null : o.toString(); }

    /** "18:00" → "18h" / "18:30" → "18h30". Se vier null/inválido, devolve null. */
    private static String formatarHora(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.split(":");
        if (p.length < 2) return s;
        String h = p[0].trim();
        String m = p[1].trim();
        if ("00".equals(m)) return h + "h";
        return h + "h" + m;
    }

    /** Endereço da loja — monta a partir dos campos detalhados, com fallback. */
    private String montarRespostaEndereco(Restaurante r) {
        // Prioriza os campos novos (rua/numero/bairro/cidade/estado/cep).
        // Se faltar tudo, usa o "endereco" antigo (campo legado).
        StringBuilder linha = new StringBuilder();
        if (notBlank(r.getRua())) {
            linha.append(r.getRua());
            if (notBlank(r.getNumero())) linha.append(", ").append(r.getNumero());
        }
        if (notBlank(r.getBairro())) {
            if (linha.length() > 0) linha.append(" — ");
            linha.append(r.getBairro());
        }
        StringBuilder cidadeEstado = new StringBuilder();
        if (notBlank(r.getCidade())) cidadeEstado.append(r.getCidade());
        if (notBlank(r.getEstado())) {
            if (cidadeEstado.length() > 0) cidadeEstado.append(" / ");
            cidadeEstado.append(r.getEstado());
        }

        StringBuilder sb = new StringBuilder("📍 *Onde estamos:*\n\n");
        boolean teveAlgo = false;
        if (linha.length() > 0)        { sb.append(linha).append("\n"); teveAlgo = true; }
        if (cidadeEstado.length() > 0) { sb.append(cidadeEstado).append("\n"); teveAlgo = true; }
        if (notBlank(r.getCep()))      { sb.append("CEP: ").append(r.getCep()).append("\n"); teveAlgo = true; }
        if (!teveAlgo && notBlank(r.getEndereco())) {
            sb.append(r.getEndereco()).append("\n");
            teveAlgo = true;
        }
        if (!teveAlgo) {
            return "Ainda não cadastramos o endereço aqui no chat. Confira no cardápio: "
                    + montarLinkCardapio(r);
        }
        return sb.toString().trim();
    }

    /** Telefone da loja — formata se vier só com dígitos. */
    private String montarRespostaTelefone(Restaurante r) {
        String tel = r.getTelefone();
        if (!notBlank(tel)) {
            return "Ainda não cadastramos o telefone aqui. "
                    + "Você pode falar comigo por aqui mesmo — é só pedir *atendente* pra falar com a equipe.";
        }
        return "📞 *Telefone:* " + tel + "\n\n"
                + "Se preferir, pode falar com a gente por aqui mesmo — digite *atendente*.";
    }

    /** CPF ou CNPJ (o que tiver cadastrado). */
    private String montarRespostaDocumento(Restaurante r) {
        boolean temCnpj = notBlank(r.getCnpj());
        boolean temCpf  = notBlank(r.getCpf());
        if (!temCnpj && !temCpf) {
            return "Não temos documento cadastrado por aqui. Posso te ajudar com mais alguma coisa?";
        }
        StringBuilder sb = new StringBuilder("📄 *Documento da loja:*\n\n");
        if (temCnpj) sb.append("CNPJ: ").append(formatarCnpj(r.getCnpj())).append("\n");
        if (temCpf)  sb.append("CPF: ").append(formatarCpf(r.getCpf())).append("\n");
        return sb.toString().trim();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String formatarCnpj(String s) {
        String d = s == null ? "" : s.replaceAll("\\D", "");
        if (d.length() != 14) return s;
        return d.substring(0,2) + "." + d.substring(2,5) + "." + d.substring(5,8)
             + "/" + d.substring(8,12) + "-" + d.substring(12,14);
    }
    private static String formatarCpf(String s) {
        String d = s == null ? "" : s.replaceAll("\\D", "");
        if (d.length() != 11) return s;
        return d.substring(0,3) + "." + d.substring(3,6) + "." + d.substring(6,9)
             + "-" + d.substring(9,11);
    }

    private String montarRespostaTaxa(Restaurante r) {
        // Taxa agora varia por bairro — não dá pra dizer um valor único.
        var bairros = r.getBairrosAtendidos();
        if (bairros == null || bairros.isEmpty()) {
            return "A taxa de entrega varia conforme o seu bairro. Coloque seu endereço no cardápio pra ver o valor exato: "
                    + montarLinkCardapio(r);
        }
        // Mostra um range pra dar uma ideia
        java.math.BigDecimal min = null, max = null;
        for (var b : bairros) {
            if (b == null || b.getTaxa() == null) continue;
            if (min == null || b.getTaxa().compareTo(min) < 0) min = b.getTaxa();
            if (max == null || b.getTaxa().compareTo(max) > 0) max = b.getTaxa();
        }
        if (min == null) {
            return "🛵 A taxa de entrega depende do seu bairro. Coloque seu endereço no cardápio pra ver o valor: "
                    + montarLinkCardapio(r);
        }
        String range = min.equals(max)
                ? "*R$ " + formatar(min) + "*"
                : "*R$ " + formatar(min) + "* a *R$ " + formatar(max) + "*";
        return "🛵 Nossa taxa de entrega varia por bairro: " + range + ".\n\n"
                + "Informe seu endereço no cardápio pra ver o valor exato pro seu bairro: "
                + montarLinkCardapio(r);
    }

    private String montarRespostaMinimo(Restaurante r) {
        if (r.getPedidoMinimo() == null || r.getPedidoMinimo().signum() == 0) {
            return "Não temos valor mínimo de pedido. 😉 Pode pedir o que quiser pelo cardápio: "
                    + montarLinkCardapio(r);
        }
        return "💰 Nosso pedido mínimo é *R$ " + formatar(r.getPedidoMinimo()) + "*.\n\n"
                + "Cardápio: " + montarLinkCardapio(r);
    }

    private String montarRespostaTempo(Restaurante r) {
        if (r.getTempoEntrega() == null) {
            return "O tempo de entrega varia conforme a região e a fila do momento. "
                    + "Geralmente sai rapidinho! 🛵";
        }
        return "⏱️ Nosso tempo médio de entrega é de cerca de *"
                + r.getTempoEntrega() + " minutos*.\n\n"
                + "(Pode variar conforme a região e o horário.)";
    }

    private String montarRespostaRegioes(Restaurante r) {
        return montarRespostaRegioes(r, null);
    }

    /**
     * Overload que tenta identificar bairro específico mencionado pelo cliente.
     * Se mensagem do tipo "vocês entregam em jardim capelinha?" e bairro
     * estiver na lista atendida → responde SIM com taxa. Senão → responde NÃO.
     * Se não cita bairro específico → lista geral como antes.
     *
     * @param textoNormalizado mensagem do cliente já normalizada (lowercase, sem acento)
     */
    private String montarRespostaRegioes(Restaurante r, String textoNormalizado) {
        var bairros = r.getBairrosAtendidos();
        if (bairros == null || bairros.isEmpty()) {
            return "Atendemos diversas regiões! 📍 Coloque seu endereço no cardápio pra ver se entregamos aí: "
                    + montarLinkCardapio(r);
        }

        // 1. Tenta achar bairro específico citado na mensagem
        if (textoNormalizado != null && !textoNormalizado.isBlank()) {
            for (var b : bairros) {
                if (b == null || b.getNome() == null || b.getNome().isBlank()) continue;
                String nomeNormalizado = normalizar(b.getNome());
                if (nomeNormalizado.isBlank()) continue;
                // Match: nome do bairro inteiro está contido na mensagem
                if (textoNormalizado.contains(nomeNormalizado)) {
                    StringBuilder ok = new StringBuilder();
                    ok.append("✅ Sim! Entregamos em *").append(b.getNome()).append("*");
                    if (b.getTaxa() != null) {
                        ok.append(".\n\n🛵 Taxa de entrega: *R$ ").append(formatar(b.getTaxa())).append("*");
                    } else {
                        ok.append(".");
                    }
                    ok.append("\n\nÉ só fazer o pedido pelo cardápio 👉 ").append(montarLinkCardapio(r));
                    return ok.toString();
                }
            }

            // Se a mensagem cita "entregam em X" / "atende em X" mas não bateu
            // com nenhum bairro cadastrado → resposta negativa específica.
            // Detecta padrão "em <algo>" / "no <algo>" / "na <algo>" depois de
            // "entrega(m)" ou "atende(m)"
            java.util.regex.Matcher mb = BAIRRO_CITADO_RX.matcher(textoNormalizado);
            if (mb.find()) {
                String bairroCitado = mb.group(2).trim();
                if (!bairroCitado.isBlank() && bairroCitado.length() > 2) {
                    StringBuilder ng = new StringBuilder();
                    ng.append("❌ Não atendemos *").append(bairroCitado).append("* infelizmente 😕\n\n");
                    ng.append("📍 *Regiões que atendemos:*\n");
                    int maxN = Math.min(bairros.size(), 15);
                    for (int i = 0; i < maxN; i++) {
                        var b = bairros.get(i);
                        if (b == null || b.getNome() == null) continue;
                        ng.append("• ").append(b.getNome()).append("\n");
                    }
                    if (bairros.size() > maxN) {
                        ng.append("• … e mais ").append(bairros.size() - maxN).append(" regiões\n");
                    }
                    return ng.toString();
                }
            }
        }

        // 2. Sem bairro específico → lista geral
        StringBuilder sb = new StringBuilder("📍 *Regiões atendidas:*\n\n");
        int max = Math.min(bairros.size(), 20); // evita msg gigante
        for (int i = 0; i < max; i++) {
            var b = bairros.get(i);
            if (b == null || b.getNome() == null) continue;
            sb.append("• ").append(b.getNome());
            if (b.getTaxa() != null) sb.append(" — R$ ").append(formatar(b.getTaxa()));
            sb.append("\n");
        }
        if (bairros.size() > max) {
            sb.append("• … e mais ").append(bairros.size() - max).append(" regiões\n");
        }
        sb.append("\nConfere o endereço completo no cardápio: ").append(montarLinkCardapio(r));
        return sb.toString();
    }

    /** Captura nome do bairro citado em frases tipo "entregam em jardim capelinha"
     *  ou "atende no centro". Grupo 2 = nome do bairro. */
    private static final java.util.regex.Pattern BAIRRO_CITADO_RX =
            java.util.regex.Pattern.compile(
                    "(entregam?|atendem?)\\s+(?:em|na|no|aqui em|aqui na|aqui no)\\s+([a-z0-9 ]+?)(?:\\?|$|\\.|,)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    // ── atendimento humano (handoff) ──

    /**
     * Lista números atualmente em modo silêncio (atendimento humano) pra essa instância.
     * Usado pelo painel pra mostrar quais conversas o dono já assumiu.
     */
    public List<AtendimentoHumano> listarAtendimentosHumanos(WhatsappInstance inst) {
        String prefix = inst.getInstanceName() + ":";
        LocalDateTime agora = LocalDateTime.now();
        List<AtendimentoHumano> out = new ArrayList<>();
        estados.forEach((k, st) -> {
            if (!k.startsWith(prefix)) return;
            if (st.silencioAte == null || agora.isAfter(st.silencioAte)) return;
            out.add(new AtendimentoHumano(k.substring(prefix.length()), st.silencioAte));
        });
        return out;
    }

    /**
     * Devolve um número pro bot — limpa o silêncio e permite que o bot volte a responder.
     * @return true se havia silêncio ativo (foi liberado); false se número não estava em modo humano.
     */
    public boolean devolverParaBot(WhatsappInstance inst, String numero) {
        String chave = inst.getInstanceName() + ":" + limparNumero(numero);
        EstadoNumero st = estados.get(chave);
        if (st == null || st.silencioAte == null) return false;
        st.silencioAte = null;
        st.saudou = false; // próxima msg volta a saudar
        log.info("[Bot] {} devolvido pro bot pelo painel", chave);
        return true;
    }

    /** DTO simples — número + quando o timer de silêncio expira automaticamente. */
    public record AtendimentoHumano(String numero, LocalDateTime silencioAte) {}

    // ── helpers ──

    private boolean contemAlguma(String texto, String... palavras) {
        for (String p : palavras) if (texto.contains(p)) return true;
        return false;
    }

    private String formatar(java.math.BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** Normaliza: lowercase + remove acentos + colapsa espaços. */
    private String normalizar(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return n.toLowerCase().trim().replaceAll("\\s+", " ");
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
        /** Primeiro nome do cliente (do pushName WhatsApp). Null se não detectado. */
        String pushName;
        /** Última intenção respondida pelo bot. Pra evitar repetir info e dar
         *  follow-up natural ("Quer que eu te ajude com mais alguma coisa?"). */
        String ultimaIntencao;
        /** Contador de mensagens nessa conversa. Saudação só na 1ª; pós 5ª sem
         *  match real, sugere atendente humano pra não frustrar. */
        int totalMensagens;
    }
}
