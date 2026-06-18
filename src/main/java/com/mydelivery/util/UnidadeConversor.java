package com.mydelivery.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.mydelivery.model.Insumo.Unidade;

/**
 * Conversão entre unidades de medida — única "fonte da verdade" pra evitar
 * cálculo errado de viabilidade quando o insumo está em uma unidade
 * (ex: LITRO) e a ficha técnica em outra (ex: ML).
 *
 * Estratégia: converter ambos para a unidade base do grupo antes de operar.
 *
 *   Volume:  base = ML        (L = 1000 ml)
 *   Massa:   base = G         (KG = 1000 g)
 *   Peça:    base = UN        (sem conversão)
 *
 * Conversão cruzada entre grupos NÃO é suportada — não faz sentido converter
 * "300 ml" em "kg" sem saber a densidade do material. Nesse caso o método
 * {@link #podeConverter} retorna false e o chamador trata o erro.
 */
public final class UnidadeConversor {

    private UnidadeConversor() {}

    /** Grupo da unidade — pra saber se duas unidades são compatíveis. */
    public enum Grupo { VOLUME, MASSA, PECA }

    public static Grupo grupoDe(Unidade u) {
        if (u == null) return Grupo.PECA;
        return switch (u) {
            case L, ML -> Grupo.VOLUME;
            case KG, G -> Grupo.MASSA;
            case UN    -> Grupo.PECA;
        };
    }

    /** True se as duas unidades podem ser convertidas entre si. */
    public static boolean podeConverter(Unidade a, Unidade b) {
        if (a == null || b == null) return a == b;
        return grupoDe(a) == grupoDe(b);
    }

    /**
     * Converte um valor para a unidade BASE do grupo (ml para volume,
     * g para massa, un para peça). Permite operações aritméticas seguras.
     */
    public static BigDecimal paraBase(BigDecimal valor, Unidade unidade) {
        if (valor == null) return BigDecimal.ZERO;
        if (unidade == null) return valor;
        return switch (unidade) {
            case L  -> valor.multiply(BigDecimal.valueOf(1000));     // 1 L = 1000 ml
            case KG -> valor.multiply(BigDecimal.valueOf(1000));     // 1 kg = 1000 g
            case ML, G, UN -> valor;
        };
    }

    /**
     * Converte um valor de uma unidade para outra (do mesmo grupo).
     * Se as unidades forem de grupos diferentes, retorna o próprio valor
     * sem alterar (o chamador deve validar via {@link #podeConverter} antes).
     */
    public static BigDecimal converter(BigDecimal valor, Unidade de, Unidade para) {
        if (valor == null) return BigDecimal.ZERO;
        if (de == null || para == null || de == para) return valor;
        if (!podeConverter(de, para)) return valor;
        BigDecimal base = paraBase(valor, de);
        return switch (para) {
            case L  -> base.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
            case KG -> base.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
            case ML, G, UN -> base;
        };
    }
}
