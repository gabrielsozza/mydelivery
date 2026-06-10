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

    /** Delay (ms) de "digitando…" antes da msg aparecer. Reduzido de 1500ms
     *  pra 600ms a pedido do operador — meta de resposta ≤4s ponta a ponta
     *  (webhook → decisão → typing → envio). */
    private static final int TYPING_DELAY_MS = 600;

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

    /** Estado por número (key = instanceName + ":" + numero). */
    private final Map<String, EstadoNumero> estados = new ConcurrentHashMap<>();

    /**
     * Processa mensagem recebida. Se bot está ligado e regras casam, responde.
     * Caso contrário, fica em silêncio.
     */
    public void processar(WhatsappInstance inst, String numero, String texto) {
        try {
            processarInterno(inst, numero, texto);
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

    private void processarInterno(WhatsappInstance inst, String numero, String texto) {
        if (!Boolean.TRUE.equals(inst.getBotAtivo())) {
            log.debug("[Bot] desligado pra instância {} — não responde", inst.getInstanceName());
            return;
        }
        if (texto == null || texto.isBlank()) return;

        String numeroLimpo = limparNumero(numero);
        String chave = inst.getInstanceName() + ":" + numeroLimpo;
        EstadoNumero st = estados.computeIfAbsent(chave, k -> new EstadoNumero());

        LocalDateTime agora = LocalDateTime.now();

        // Modo silêncio: cliente pediu atendente humano → bot fica quieto.
        // Janela deslizante: cada nova msg do cliente reseta o timer pros próximos N min.
        // Assim, se cliente+atendente ficam X min em silêncio, o bot retoma sozinho.
        if (st.silencioAte != null && agora.isBefore(st.silencioAte)) {
            st.silencioAte = agora.plusMinutes(props.getBot().getSilencioMinutos());
            log.debug("[Bot] em silêncio pra {} (renovado até {})", chave, st.silencioAte);
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
            return; // sem regra casando → silêncio
        }

        whatsappService.enviarMensagem(inst, numeroLimpo, resposta, TYPING_DELAY_MS);
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
    private String decidirResposta(Restaurante r, String texto, EstadoNumero st) {
        String t = normalizar(texto);
        ConfiguracaoRestaurante cfg = configRepo.findByRestauranteId(r.getId()).orElse(null);

        // 0. Confirmação automática de pedido — disparada pelo botão
        // "Confirmar via WhatsApp" do cardápio digital. O cardápio envia uma
        // mensagem com o marcador [MyDelivery#PEDIDO_N], que o cliente NÃO
        // digitaria por conta própria. Match exato pra evitar falso positivo.
        // Resposta amigável tranquilizando o cliente.
        java.util.regex.Matcher mPed = MARCADOR_PEDIDO_RX.matcher(texto != null ? texto : "");
        if (mPed.find()) {
            String num = mPed.group(1);
            return "Olá! 👋 Recebemos o seu pedido *#" + num + "* aqui no "
                    + r.getNome() + ".\n\n"
                    + "Ele já está na cozinha e vai sair quentinho pra você. "
                    + "É só aguardar um instantinho que ele bate na sua porta! 🍽️🛵";
        }

        // 1. Atendente humano — prioridade máxima
        if (contemAlguma(t, "atendente", "humano", "pessoa", "falar com alguem",
                "falar com voce", "atendimento humano", "operador")) {
            st.pediuAtendente = true;
            return "Sem problema! 👤 Vou transferir você pra um atendente humano da " + r.getNome() + ".\n\n"
                    + "Em instantes alguém da equipe responde aqui mesmo. 😊";
        }

        // 2. Regiões / bairros atendidos
        if (contemAlguma(t, "regiao", "regioes", "atende", "atendem", "atende aqui",
                "entrega aqui", "bairro", "bairros", "atendido", "cobertura")) {
            return montarRespostaRegioes(r);
        }

        // 3. Taxa de entrega
        if (contemAlguma(t, "taxa", "frete", "valor da entrega", "quanto a entrega", "custo de entrega")) {
            return montarRespostaTaxa(r);
        }

        // 4. Pedido mínimo
        if (contemAlguma(t, "minimo", "pedido min", "valor minimo", "quanto preciso pedir")) {
            return montarRespostaMinimo(r);
        }

        // 5. Tempo de entrega
        if (contemAlguma(t, "demora", "quanto tempo", "tempo de entrega", "leva quanto",
                "tempo medio", "demorar", "rapido")) {
            return montarRespostaTempo(r);
        }

        // 6. Cardápio
        if (contemAlguma(t, "cardapio", "menu", "produtos", "comprar", "pedir",
                "fazer pedido", "fazer um pedido")) {
            String link = montarLinkCardapio(r);
            // Se loja fechada: envia link MAS sem incentivar compra
            if (!Boolean.TRUE.equals(r.getAberto())) {
                return "A loja está *fechada* no momento 😅\n\n"
                        + "Mas você ainda pode dar uma olhada no cardápio 👉 " + link
                        + "\n\n" + montarLinhaHorarioHoje(r);
            }
            return "Aqui está nosso cardápio 👉 " + link + "\n\nÉ só escolher os itens e finalizar pelo site. 🍽️";
        }

        // 7. Horário / aberto / fechado / funcionando — cobre variações masculino/feminino/plural
        if (contemAlguma(t, "horario", "horário", "que horas", "abre", "aberto", "aberta",
                "abertos", "abertas", "ta aberto", "ta aberta", "esta aberto", "esta aberta",
                "estao abertos", "estao abertas", "funcionamento", "funcionando", "funciona",
                "atendendo", "ainda atende", "ta atendendo", "fechado", "fechada", "fechados",
                "ta funcionando", "esta funcionando", "vcs estao", "voces estao")) {
            return montarRespostaHorario(r);
        }

        // 7a. Endereço / localização ("onde fica", "qual endereço", "como chegar")
        if (contemAlguma(t, "endereco", "onde fica", "onde voces ficam", "onde estao",
                "localizacao", "localização", "como chegar", "qual o endereco",
                "qual endereco", "lugar", "endereço")) {
            return montarRespostaEndereco(r);
        }

        // 7b. Telefone / contato
        if (contemAlguma(t, "telefone", "numero", "contato", "telefonar",
                "ligar", "qual o telefone", "fone", "whats")) {
            return montarRespostaTelefone(r);
        }

        // 7c. CNPJ / CPF — POR POLÍTICA, o bot não revela documentos fiscais.
        // Mesmo que estejam cadastrados, são dados sensíveis. Respondemos
        // educadamente direcionando pra falar com a equipe.
        if (contemAlguma(t, "cnpj", "cpf", "documento", "razao social", "razão social")) {
            return "Essa informação não está disponível no atendimento automático. "
                    + "Se precisar do dado fiscal pra nota, fale com a equipe — digite *atendente*.";
        }

        // 8. Agradecimento curto — responde gentil sem repetir menu
        if (contemAlguma(t, "obrigado", "obrigada", "valeu", "vlw", "thanks")) {
            return "Por nada! 😊 Qualquer coisa é só chamar.";
        }

        // 9. Saudação / 1ª interação → apresenta o bot
        boolean ehSaudacao = contemAlguma(t, "oi", "ola", "ola ", "opa", "bom dia",
                "boa tarde", "boa noite", "hey", "eai", "e ai", "tudo bem");
        if (!st.saudou || ehSaudacao) {
            return montarApresentacao(r);
        }

        // 10. Fallback: já saudou e não entendeu — mostra menu curto
        return montarMenuCurto(r);
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
        String link = montarLinkCardapio(r);
        boolean aberto = Boolean.TRUE.equals(r.getAberto());

        StringBuilder sb = new StringBuilder();
        sb.append("Olá! 👋 Aqui é da *").append(r.getNome()).append("*.\n\n");

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
        var bairros = r.getBairrosAtendidos();
        if (bairros == null || bairros.isEmpty()) {
            return "Atendemos diversas regiões! 📍 Coloque seu endereço no cardápio pra ver se entregamos aí: "
                    + montarLinkCardapio(r);
        }
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
    }
}
