package com.mydelivery.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Planos comerciais do MyDelivery.
 *
 * Os valores aqui são a fonte da verdade — application.properties podia trazer,
 * mas como mudam raramente e ficam mais legíveis aqui, deixei como constantes.
 *
 * Regras de pagamento:
 *  - MENSAL:    cobrança recorrente (cartão de crédito)
 *  - SEMESTRAL: pagamento único PIX/cartão (acesso por 6 meses)
 *  - ANUAL:    pagamento único PIX/cartão (acesso por 12 meses, melhor custo)
 */
public enum Plano {

    // Tipos de onboarding declarados como constantes (não enum aninhado pra evitar
    // forward reference). Definidos como strings simples — frontend lê e mostra
    // o flow correspondente. Expansível: basta adicionar novo tipo aqui.
    //
    //   BASICO      → guia rápido (chip checklist, ~3 passos): cardápio, foto, abrir loja
    //   PADRAO      → guia médio (~5 passos): + WhatsApp + PIX
    //   PREMIUM     → guia completo + onboarding 1:1 agendável (anual)
    //
    // Cada plano declara qual tipo usa. Frontend de planos.html já exibe o tipo
    // ANUAL com "Onboarding personalizado" — agora essa info vem do backend.

    MENSAL    ("Mensal",     new BigDecimal("49.90"),  1,  false, true,  false, "BASICO"),
    SEMESTRAL ("Semestral",  new BigDecimal("300.00"), 6,  false, true,  true,  "PADRAO"),
    ANUAL     ("Anual",      new BigDecimal("550.00"), 12, true,  true,  true,  "PREMIUM");

    private final String nomeExibicao;
    private final BigDecimal valor;        // total cobrado neste plano
    private final int duracaoMeses;
    private final boolean recomendado;
    private final boolean aceitaCartao;
    private final boolean aceitaPix;
    /** Tipo de onboarding entregue ao restaurante após ativar este plano. */
    private final String onboardingTipo;

    Plano(String nomeExibicao, BigDecimal valor, int duracaoMeses,
          boolean recomendado, boolean aceitaCartao, boolean aceitaPix, String onboardingTipo) {
        this.nomeExibicao = nomeExibicao;
        this.valor = valor;
        this.duracaoMeses = duracaoMeses;
        this.recomendado = recomendado;
        this.aceitaCartao = aceitaCartao;
        this.aceitaPix = aceitaPix;
        this.onboardingTipo = onboardingTipo;
    }

    public String getNomeExibicao() { return nomeExibicao; }
    public BigDecimal getValor() { return valor; }
    public int getDuracaoMeses() { return duracaoMeses; }
    public boolean isRecomendado() { return recomendado; }
    public boolean isAceitaCartao() { return aceitaCartao; }
    public boolean isAceitaPix() { return aceitaPix; }
    public String getOnboardingTipo() { return onboardingTipo; }

    /** Preço efetivo por mês — usado pra mostrar economia no card do plano. */
    public BigDecimal getValorPorMes() {
        return valor.divide(new BigDecimal(duracaoMeses), 2, RoundingMode.HALF_UP);
    }

    /** Economia em relação ao mensal — só faz sentido pra semestral/anual. */
    public BigDecimal getEconomiaTotal() {
        BigDecimal seFosseMensal = MENSAL.valor.multiply(new BigDecimal(duracaoMeses));
        BigDecimal eco = seFosseMensal.subtract(valor);
        return eco.compareTo(BigDecimal.ZERO) > 0 ? eco : BigDecimal.ZERO;
    }

    public int getEconomiaPercentual() {
        BigDecimal seFosseMensal = MENSAL.valor.multiply(new BigDecimal(duracaoMeses));
        if (seFosseMensal.compareTo(BigDecimal.ZERO) <= 0) return 0;
        BigDecimal eco = seFosseMensal.subtract(valor);
        return eco.multiply(new BigDecimal("100"))
                .divide(seFosseMensal, 0, RoundingMode.HALF_UP)
                .intValue();
    }
}
