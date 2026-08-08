package com.mydelivery.service.whatsapp;

/**
 * Lançada quando o circuit breaker do cliente WhatsApp (Uazapi) está ABERTO —
 * i.e., muitas falhas consecutivas recentes, então paramos de bater no
 * upstream por um tempo pra ele respirar.
 *
 * <p>Tratamento a montante:
 * <ul>
 *   <li>{@code WhatsappService.conectar} converte em estado de "serviço em
 *       preparação" no painel (sem stacktrace pro dono);</li>
 *   <li>{@code WhatsappHealthService} NÃO dispara auto-reconexão durante CB
 *       aberto (só pioraria);</li>
 *   <li>o Bot responde "instabilidade momentânea, tente em 1min".</li>
 * </ul>
 *
 * <p>Nome mantido por compatibilidade histórica (era aninhada em
 * {@code EvolutionClient}, removido na migração pra Uazapi).
 */
public class EvolutionCircuitOpenException extends RuntimeException {
    public EvolutionCircuitOpenException(String msg) { super(msg); }
}
