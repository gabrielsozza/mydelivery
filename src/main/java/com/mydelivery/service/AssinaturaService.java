package com.mydelivery.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mydelivery.model.Assinatura;
import com.mydelivery.model.PagamentoMensalidade;
import com.mydelivery.model.Plano;
import com.mydelivery.model.PlanoCatalogo;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.AssinaturaRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Regras de assinatura/trial/inadimplência expostas como API leve pro frontend.
 *
 * Não bloqueia requisições no filter chain (decisão anterior — causou regressão).
 * O frontend lê o status via /assinatura/status e renderiza overlays/banners
 * conforme a fase atual.
 *
 * Fases (calculadas em runtime, não persistidas):
 *  - TRIAL       : trial vigente
 *  - TRIAL_ALERTA: trial vigente mas faltam ≤ N dias (mostra modal)
 *  - ATIVA       : plano pago e válido
 *  - ALERTA      : até 5 dias após vencer (avisos leves)
 *  - RESTRICAO   : 5–10 dias após vencer (limita funcionalidades)
 *  - BLOQUEIO    : 10+ dias após vencer (overlay total mas pode regularizar)
 *  - CANCELADA   : restaurante cancelou ativamente
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssinaturaService {

    private final AssinaturaRepository assinaturaRepository;
    private final RestauranteRepository restauranteRepository;
    private final PlanoCatalogoService planoCatalogoService;

    @Value("${app.assinatura.aviso-trial-dias:5}")
    private int avisoTrialDias;

    @Value("${app.assinatura.restricao-dias:5}")
    private int restricaoDias;

    @Value("${app.assinatura.bloqueio-dias:10}")
    private int bloqueioDias;

    /**
     * Devolve o estado completo da assinatura pro frontend renderizar tudo:
     * banners de trial, modal de aviso, bloqueio elegante.
     */
    // Não-readonly: pode persistir auto-correção de validaAte inflado (bug histórico
    // que somava períodos a cada clique). A leitura segue idempotente — só escreve
    // se detectar inconsistência real.
    @Transactional
    public Map<String, Object> obterStatus(Restaurante r) {
        Assinatura a = assinaturaRepository.findByRestauranteId(r.getId()).orElse(null);
        LocalDateTime agora = LocalDateTime.now();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planosDisponiveis", listarPlanos());

        if (a == null) {
            // Restaurante sem assinatura (caso raro — pode ser bug de cadastro antigo)
            out.put("fase", "TRIAL");
            out.put("status", "TRIAL");
            out.put("planoAtual", null);
            out.put("diasRestantes", 0);
            out.put("validaAte", null);
            out.put("podeAcessarTudo", true);
            out.put("mensagem", "Bem-vindo ao MyDelivery! Configure seu plano para começar.");
            return out;
        }

        out.put("status", a.getStatus().name());
        out.put("planoAtual", a.getPlano() != null ? a.getPlano().name() : null);
        out.put("planoNome", a.getPlano() != null ? a.getPlano().getNomeExibicao() : "Período gratuito");
        out.put("valor", a.getValor());
        out.put("trialInicio", a.getTrialInicio() != null ? a.getTrialInicio().toString() : null);
        out.put("trialFim", a.getTrialFim() != null ? a.getTrialFim().toString() : null);
        out.put("validaAte", a.getValidaAte() != null ? a.getValidaAte().toString() : null);
        out.put("proximaCobranca", a.getProximaCobranca() != null ? a.getProximaCobranca().toString() : null);

        // ── Calcula fase ──
        if (a.getStatus() == Assinatura.Status.CANCELADA) {
            // Cancelada mas ainda no período pago: acesso mantido até validaAte.
            if (a.getValidaAte() != null && a.getValidaAte().isAfter(agora)) {
                int diasRest = diasEntre(agora, a.getValidaAte());
                out.put("fase", "CANCELADA");
                out.put("diasRestantes", diasRest);
                out.put("podeAcessarTudo", true);
                out.put("mensagem", "Assinatura cancelada. Você ainda tem acesso por "
                        + diasRest + " dia" + (diasRest == 1 ? "" : "s") + ".");
            } else {
                out.put("fase", "CANCELADA");
                out.put("diasRestantes", 0);
                out.put("podeAcessarTudo", false);
                out.put("mensagem", "Sua assinatura foi cancelada. Assine novamente para continuar.");
            }
            return out;
        }

        if (a.getStatus() == Assinatura.Status.TRIAL || a.getPlano() == null) {
            LocalDateTime fim = a.getTrialFim() != null ? a.getTrialFim() : r.getTrialExpiraEm();
            int dias = diasEntre(agora, fim);
            int total = a.getTrialInicio() != null
                    ? diasEntre(a.getTrialInicio(), fim)
                    : 32;
            int usados = Math.max(0, total - dias);
            int pct = total > 0 ? Math.min(100, Math.round(100f * usados / total)) : 100;

            out.put("diasRestantes", Math.max(0, dias));
            out.put("trialTotal", total);
            out.put("trialUsados", usados);
            out.put("trialPercentualUsado", pct);
            out.put("podeAcessarTudo", true);

            if (dias <= 0) {
                // Trial expirou e não assinou — entra em RESTRICAO (não bloqueia tudo, dá uma graça)
                int aposVencimento = diasEntre(fim, agora);
                return preencherFasePosVencimento(out, fim, aposVencimento, "Seu período gratuito acabou.");
            }
            if (dias <= avisoTrialDias) {
                out.put("fase", "TRIAL_ALERTA");
                out.put("mensagem", "Seu período gratuito termina em " + dias + " dia" + (dias == 1 ? "" : "s") + ".");
            } else {
                out.put("fase", "TRIAL");
                out.put("mensagem", "Você tem " + dias + " dias gratuitos restantes.");
            }
            return out;
        }

        // Assinatura paga — auto-corrige validaAte fora da janela permitida pelo plano.
        // Causa do bug "214 dias": versões antigas do ativarPlano somavam +1 mês a cada
        // chamada, inflando validaAte muito além de plano.duracaoMeses. Aqui detectamos
        // assinaturas com dados inconsistentes e corrigimos persistindo no banco.
        LocalDateTime fimVigencia = a.getValidaAte() != null ? a.getValidaAte() : a.getProximaCobranca();
        if (fimVigencia == null) {
            out.put("fase", "ATIVA");
            out.put("diasRestantes", 30);
            out.put("podeAcessarTudo", true);
            out.put("mensagem", "Plano ativo.");
            return out;
        }

        // Janela máxima permitida: ultimaCobranca + duracaoMeses do plano.
        // Se ultimaCobranca não existe, usa agora como referência conservadora.
        LocalDateTime base = a.getUltimaCobranca() != null ? a.getUltimaCobranca() : agora;
        LocalDateTime maxValidaAte = base.plusMonths(a.getPlano().getDuracaoMeses())
                .plusDays(3); // tolerância de 3 dias pra evitar correção em borda
        if (fimVigencia.isAfter(maxValidaAte)) {
            LocalDateTime corrigido = base.plusMonths(a.getPlano().getDuracaoMeses());
            log.warn("[Assinatura] validaAte inflado pra restaurante #{} ({} → {}). Corrigindo.",
                    r.getId(), fimVigencia, corrigido);
            a.setValidaAte(corrigido);
            a.setProximaCobranca(corrigido);
            assinaturaRepository.save(a);
            fimVigencia = corrigido;
            out.put("validaAte", corrigido.toString());
            out.put("proximaCobranca", corrigido.toString());
        }

        int diasParaVencer = diasEntre(agora, fimVigencia);
        // Teto adicional: jamais reporta mais dias do que a duração total do plano.
        // É proteção em profundidade — se algum dado anômalo passar pela correção acima.
        int maxDiasPlano = a.getPlano().getDuracaoMeses() * 31; // 31 = pior caso meses longos
        if (diasParaVencer > maxDiasPlano) diasParaVencer = maxDiasPlano;

        if (diasParaVencer > 0) {
            out.put("fase", "ATIVA");
            out.put("diasRestantes", diasParaVencer);
            out.put("podeAcessarTudo", true);
            out.put("mensagem", "Plano " + a.getPlano().getNomeExibicao()
                    + " ativo · próxima renovação em " + diasParaVencer + " dias.");
            return out;
        }

        // Vencido
        int diasAposVencimento = diasEntre(fimVigencia, agora);
        return preencherFasePosVencimento(out, fimVigencia, diasAposVencimento, "Pagamento em atraso.");
    }

    /**
     * Cria/atualiza assinatura ao confirmar pagamento.
     * Chamado pelo fluxo de checkout — no MVP, em modo manual; integração com MP
     * cobra normalmente via /api/pagamentos/* e marca aqui após confirmação.
     */
    @Transactional
    public Assinatura ativarPlano(Restaurante r, Plano plano, String metodoPagamento, String referenciaGateway) {
        Assinatura a = assinaturaRepository.findByRestauranteId(r.getId())
                .orElseGet(() -> Assinatura.builder()
                        .restaurante(r)
                        .valor(plano.getValor())
                        .build());

        LocalDateTime agora = LocalDateTime.now();

        // ── Idempotência: já está ATIVA no mesmo plano e ainda vigente?
        // Cliques repetidos no "confirmar plano" não devem somar mais um período.
        // Comportamento correto: a vigência só estende em renovação REAL (cobrança
        // automática), não a cada chamada do endpoint.
        if (a.getStatus() == Assinatura.Status.ATIVA
                && a.getPlano() == plano
                && a.getValidaAte() != null
                && a.getValidaAte().isAfter(agora)) {
            log.info("Plano {} já ativo até {} para restaurante #{} — chamada idempotente",
                    plano, a.getValidaAte(), r.getId());
            return a;
        }

        // Calcula vigência a partir de AGORA (não soma sobre vigência anterior).
        // Renovação automática real (futura, via gateway) é quem deve estender.
        LocalDateTime novoFim = agora.plusMonths(plano.getDuracaoMeses());

        a.setStatus(Assinatura.Status.ATIVA);
        a.setPlano(plano);
        a.setValor(plano.getValor());
        a.setValidaAte(novoFim);
        a.setProximaCobranca(novoFim);
        a.setUltimaCobranca(agora);

        Assinatura salva = assinaturaRepository.save(a);

        // Atualiza status do restaurante pra ATIVO
        r.setStatus(Restaurante.Status.ATIVO);
        r.setBloqueadoEm(null);
        r.setMotivoBloqueio(null);
        restauranteRepository.save(r);

        log.info("✅ Plano {} ativado para restaurante #{} até {}", plano, r.getId(), novoFim);
        return salva;
    }

    /**
     * Programa um plano pra ser cobrado AO FINAL DO TRIAL.
     * O cartão já foi salvo no MP (Customer + Card) — esta função apenas
     * marca a assinatura como PROGRAMADA com a referência do gateway.
     *
     * Restaurante CONTINUA em TRIAL (não é promovido pra ATIVO ainda).
     * Um job futuro detecta {@code proximaCobranca <= agora} + status PROGRAMADA
     * e dispara a cobrança real via MP usando customer+card salvos.
     */
    @Transactional
    public Assinatura programarPlanoTrialCartao(Restaurante r, Plano plano, String referenciaGateway) {
        Assinatura a = assinaturaRepository.findByRestauranteId(r.getId())
                .orElseGet(() -> Assinatura.builder()
                        .restaurante(r)
                        .valor(plano.getValor())
                        .build());

        LocalDateTime agora = LocalDateTime.now();
        LocalDateTime trialFim = r.getTrialExpiraEm() != null ? r.getTrialExpiraEm() : agora.plusDays(7);
        // Vigência futura = começa quando trial acaba e dura plano.duracaoMeses
        LocalDateTime novoFim = trialFim.plusMonths(plano.getDuracaoMeses());

        a.setStatus(Assinatura.Status.PENDENTE); // PENDENTE = cartão validado, aguardando trial expirar
        a.setPlano(plano);
        a.setValor(plano.getValor());
        a.setMetodoPagamento("CARTAO");
        a.setReferenciaGateway(referenciaGateway);
        a.setValidaAte(novoFim);
        a.setProximaCobranca(trialFim); // job de cobrança usa isso
        // ultimaCobranca fica null até a cobrança real acontecer

        Assinatura salva = assinaturaRepository.save(a);
        log.info("📅 Plano {} programado para restaurante #{} — cobrança em {} (ref={})",
                plano, r.getId(), trialFim, referenciaGateway);
        return salva;
    }

    /**
     * Calcula crédito proporcional (pró-rata) do plano atual.
     * Se restam X dias dos N totais, devolve (X/N) * valorPago.
     * Zero se já vencido ou sem assinatura.
     */
    public java.math.BigDecimal calcularCreditoProRata(Restaurante r) {
        Assinatura a = assinaturaRepository.findByRestauranteId(r.getId()).orElse(null);
        if (a == null || a.getStatus() != Assinatura.Status.ATIVA
                || a.getValidaAte() == null || a.getUltimaCobranca() == null) {
            return java.math.BigDecimal.ZERO;
        }
        LocalDateTime agora = LocalDateTime.now();
        if (a.getValidaAte().isBefore(agora)) return java.math.BigDecimal.ZERO;

        long totalSegundos = java.time.Duration.between(a.getUltimaCobranca(), a.getValidaAte()).toSeconds();
        long restanteSegundos = java.time.Duration.between(agora, a.getValidaAte()).toSeconds();
        if (totalSegundos <= 0 || restanteSegundos <= 0) return java.math.BigDecimal.ZERO;

        java.math.BigDecimal proporcao = new java.math.BigDecimal(restanteSegundos)
                .divide(new java.math.BigDecimal(totalSegundos), 4, java.math.RoundingMode.HALF_UP);
        return a.getValor().multiply(proporcao).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Troca de plano: UPGRADE ou DOWNGRADE.
     *
     * - UPGRADE (novo > atual): aplica crédito pró-rata do plano atual e cobra
     *   a diferença AGORA. Vigência do novo plano começa imediato.
     * - DOWNGRADE (novo < atual): mantém plano atual ativo até validaAte, e
     *   marca novo plano como PROGRAMADO. Quando vencer, novo plano entra.
     *
     * Retorna info com {tipoOperacao, creditoAplicado, valorACobrar, mensagem}
     * pra frontend decidir se redireciona pra pagamento.
     */
    @Transactional
    public Map<String, Object> trocarPlano(Restaurante r, Plano novoPlano) {
        Assinatura a = assinaturaRepository.findByRestauranteId(r.getId())
                .orElseThrow(() -> new RuntimeException("Sem assinatura ativa pra trocar"));
        Plano atual = a.getPlano();
        if (atual == null) throw new RuntimeException("Assinatura sem plano definido");
        if (atual == novoPlano) throw new RuntimeException("Você já está nesse plano");

        boolean isUpgrade = novoPlano.getValor().compareTo(atual.getValor()) > 0;
        Map<String, Object> out = new java.util.LinkedHashMap<>();

        if (isUpgrade) {
            java.math.BigDecimal credito = calcularCreditoProRata(r);
            java.math.BigDecimal valorACobrar = novoPlano.getValor().subtract(credito);
            if (valorACobrar.signum() < 0) valorACobrar = java.math.BigDecimal.ZERO;

            out.put("tipoOperacao", "UPGRADE");
            out.put("planoAtual", atual.name());
            out.put("novoPlano", novoPlano.name());
            out.put("valorIntegralNovo", novoPlano.getValor());
            out.put("creditoAplicado", credito);
            out.put("valorACobrar", valorACobrar);
            out.put("mensagem", "Upgrade aprovado. Aplicamos R$ " + credito + " de crédito proporcional. "
                    + "Você pagará R$ " + valorACobrar + " pra ativar o novo plano agora.");
            // Caller (controller) decide se chama o fluxo de cobrança imediata com esse valor.
            return out;
        }

        // DOWNGRADE — agenda pra quando o atual vencer
        a.setPlano(novoPlano); // marca o novo
        a.setValor(novoPlano.getValor());
        // proximaCobranca continua = validaAte (já estava agendado)
        // status fica ATIVA até validaAte chegar (cobrança automática faz transição)
        assinaturaRepository.save(a);

        out.put("tipoOperacao", "DOWNGRADE");
        out.put("planoAtual", atual.name());
        out.put("novoPlano", novoPlano.name());
        out.put("entraEmVigorEm", a.getValidaAte() != null ? a.getValidaAte().toString() : null);
        out.put("mensagem", "Downgrade agendado. Seu plano " + atual.getNomeExibicao()
                + " continua até " + a.getValidaAte() + ". A partir daí entra o "
                + novoPlano.getNomeExibicao() + ".");
        log.info("📉 Downgrade restaurante={}: {}→{} (em vigor: {})",
                r.getId(), atual, novoPlano, a.getValidaAte());
        return out;
    }

    /** Troca apenas o método de pagamento (PIX↔CARTAO). Não cobra nada. */
    @Transactional
    public Assinatura trocarMetodo(Restaurante r, String novoMetodo) {
        Assinatura a = assinaturaRepository.findByRestauranteId(r.getId())
                .orElseThrow(() -> new RuntimeException("Sem assinatura"));
        String m = novoMetodo == null ? "" : novoMetodo.toUpperCase();
        if (!"PIX".equals(m) && !"CARTAO".equals(m)) {
            throw new RuntimeException("Método inválido. Use PIX ou CARTAO");
        }
        if ("PIX".equals(m) && a.getPlano() != null && a.getPlano().getDuracaoMeses() <= 1) {
            throw new RuntimeException("PIX disponível apenas para plano Semestral ou Anual");
        }
        a.setMetodoPagamento(m);
        log.info("Método pagamento trocado pra {} no restaurante #{}", m, r.getId());
        return assinaturaRepository.save(a);
    }

    /** Helper pra controllers acessarem a Assinatura atual. */
    public java.util.Optional<Assinatura> obterAssinatura(Restaurante r) {
        return assinaturaRepository.findByRestauranteId(r.getId());
    }

    /** Atualiza a referência de cartão salvo após Brick re-tokenizar. */
    @Transactional
    public Assinatura atualizarReferenciaCartao(Restaurante r, String novaReferencia) {
        Assinatura a = assinaturaRepository.findByRestauranteId(r.getId())
                .orElseThrow(() -> new RuntimeException("Sem assinatura"));
        a.setReferenciaGateway(novaReferencia);
        a.setMetodoPagamento("CARTAO");
        return assinaturaRepository.save(a);
    }

    /**
     * Cancela a assinatura. Mantém o acesso até o fim do período já pago
     * (validaAte) — é o padrão SaaS: você cancelou em 10/05 mas o plano vai
     * até 31/05 mesmo. Só interrompe renovação automática.
     */
    @Transactional
    public Assinatura cancelarPlano(Restaurante r) {
        Assinatura a = assinaturaRepository.findByRestauranteId(r.getId())
                .orElseThrow(() -> new RuntimeException("Assinatura não encontrada"));
        if (a.getStatus() != Assinatura.Status.ATIVA) {
            throw new RuntimeException("Sem plano ativo para cancelar");
        }
        a.setStatus(Assinatura.Status.CANCELADA);
        a.setCanceladoEm(LocalDateTime.now());
        a.setProximaCobranca(null); // não renova mais
        Assinatura salva = assinaturaRepository.save(a);
        log.info("Plano cancelado para restaurante #{}. Acesso mantido até {}",
                r.getId(), a.getValidaAte());
        return salva;
    }

    // ── Helpers ──

    private Map<String, Object> preencherFasePosVencimento(Map<String, Object> out, LocalDateTime venc,
                                                           int diasAposVencimento, String mensagemBase) {
        out.put("diasRestantes", 0);
        out.put("diasAposVencimento", diasAposVencimento);
        if (diasAposVencimento < restricaoDias) {
            out.put("fase", "ALERTA");
            out.put("podeAcessarTudo", true);
            out.put("mensagem", mensagemBase + " Renove pra continuar sem interrupções.");
        } else if (diasAposVencimento < bloqueioDias) {
            out.put("fase", "RESTRICAO");
            out.put("podeAcessarTudo", false);
            out.put("mensagem", "Algumas funcionalidades foram limitadas. Regularize sua assinatura.");
        } else {
            out.put("fase", "BLOQUEIO");
            out.put("podeAcessarTudo", false);
            out.put("mensagem", "Assine o plano para continuar faturando.");
        }
        return out;
    }

    private int diasEntre(LocalDateTime de, LocalDateTime ate) {
        if (de == null || ate == null) return 0;
        long h = ChronoUnit.HOURS.between(de, ate);
        // arredonda pra cima — "1 hora restante" ainda mostra 1 dia restante
        return (int) Math.max(0, Math.ceil(h / 24.0));
    }

    /**
     * Lista planos disponíveis pro restaurante exibir.
     *
     * Fonte: tabela {@code planos_catalog} (editável pelo admin). Se a tabela
     * estiver vazia por algum motivo extremo (seed não rodou), cai pro enum
     * histórico — garante que nunca devolve lista vazia em produção.
     */
    private List<Map<String, Object>> listarPlanos() {
        List<PlanoCatalogo> ativos = planoCatalogoService.listarAtivos();
        if (!ativos.isEmpty()) {
            List<Map<String, Object>> lista = new ArrayList<>();
            for (PlanoCatalogo p : ativos) lista.add(planoCatalogoService.toMapRestaurante(p));
            return lista;
        }
        // Fallback de segurança — só roda se tabela ficou vazia (caso patológico)
        log.warn("[Assinatura] planos_catalog vazio — usando fallback do enum legado");
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Plano p : Plano.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.name());
            m.put("nome", p.getNomeExibicao());
            m.put("valor", p.getValor());
            m.put("duracaoMeses", p.getDuracaoMeses());
            m.put("valorPorMes", p.getValorPorMes());
            m.put("economiaTotal", p.getEconomiaTotal());
            m.put("economiaPercentual", p.getEconomiaPercentual());
            m.put("recomendado", p.isRecomendado());
            m.put("aceitaCartao", p.isAceitaCartao());
            m.put("aceitaPix", p.isAceitaPix());
            m.put("recorrente", p == Plano.MENSAL);
            m.put("onboardingTipo", p.getOnboardingTipo());
            lista.add(m);
        }
        return lista;
    }
}
