package com.mydelivery.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mydelivery.model.Restaurante;

/**
 * Lógica central de horário da loja — usado por:
 *  - HorarioLojaScheduler (cron 1min): abre/fecha automaticamente.
 *  - PedidoService.criarPedido: recusa se não aceitar pedidos agora.
 *  - PublicController: expõe estado calculado no GET do restaurante.
 *
 * Timezone: SEMPRE America/Sao_Paulo (banco já está nesse TZ pelo
 * jdbc url, mas garantimos aqui no nível Java pra não depender da
 * configuração do servidor — Railway pode estar em UTC).
 *
 * Horários cruzando madrugada (ex: abertura 18:00, fechamento 02:00):
 * tratamos como "fim > início" → range invertido, ainda funciona.
 * Se fechamento <= abertura, consideramos que cruza meia-noite.
 *
 * Múltiplas janelas por dia (ex: almoço 11:00-14:00 + jantar 18:00-23:00):
 * o JSON do dia pode ter {@code intervalos: [{abertura,fechamento},...]} —
 * a loja fica "aberta" enquanto o relógio estiver dentro de QUALQUER
 * intervalo. Se só houver {@code abertura/fechamento} (formato antigo),
 * tratamos como 1 intervalo — 100% backward-compatible.
 */
@Service
public class HorarioLojaService {

    private static final ZoneId TZ = ZoneId.of("America/Sao_Paulo");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Um intervalo de funcionamento (uma "janela") no dia. */
    public static class Intervalo {
        public final LocalTime abertura;
        public final LocalTime fechamento;
        public Intervalo(LocalTime a, LocalTime f) { this.abertura = a; this.fechamento = f; }
    }

    /** Resultado consolidado pro caller decidir o que fazer. */
    public static class EstadoHorario {
        public boolean horarioConfigurado;  // tem horario pra hoje cadastrado?
        public boolean dentroHorario;       // hora atual ∈ qualquer intervalo
        public boolean dentroCutoff;        // estamos nos N min antes do fim do intervalo atual?
        /** Todos os intervalos do dia (pode ser vazio). Sempre != null. */
        public List<Intervalo> intervalos = new ArrayList<>();
        /**
         * Compat com callers antigos: primeira janela do dia. NÃO representa
         * a janela ATIVA nem o horário exposto ao cliente — use {@link #intervalos}.
         */
        public LocalTime abertura;
        public LocalTime fechamento;

        /** A loja deveria estar visualmente aberta agora? */
        public boolean deveriaEstarAberta() { return dentroHorario; }

        /** Aceita novos pedidos? (não no cutoff E dentro do horário) */
        public boolean aceitandoPedidos() { return dentroHorario && !dentroCutoff; }
    }

    /** Estado calculado pra o restaurante agora. Nunca lança — null safe. */
    public EstadoHorario calcular(Restaurante r) {
        EstadoHorario e = new EstadoHorario();
        if (r == null) return e;
        LocalDateTime agora = LocalDateTime.now(TZ);
        Map<String, ?> hoje = horarioDoDia(r, agora.toLocalDate());
        if (hoje == null) return e; // dia sem horário (fechado o dia todo)

        Object abertoFlag = hoje.get("aberto");
        if (abertoFlag != null && Boolean.FALSE.equals(abertoFlag)) return e;

        List<Intervalo> intervalos = extrairIntervalos(hoje);
        if (intervalos.isEmpty()) return e;

        e.horarioConfigurado = true;
        e.intervalos = intervalos;
        e.abertura = intervalos.get(0).abertura;
        e.fechamento = intervalos.get(intervalos.size() - 1).fechamento;

        // Verifica cada janela; a primeira que "conter" o momento atual manda.
        LocalTime agoraLT = agora.toLocalTime();
        Intervalo ativo = null;
        for (Intervalo iv : intervalos) {
            if (estaDentro(agoraLT, iv.abertura, iv.fechamento)) { ativo = iv; break; }
        }
        e.dentroHorario = ativo != null;

        if (ativo != null && Boolean.TRUE.equals(r.getPararPedidosAntesFechamento())) {
            int cutoff = r.getMinutosAntesFechamento() != null ? r.getMinutosAntesFechamento() : 0;
            if (cutoff > 0) {
                // Cutoff é relativo AO FIM DA JANELA ATIVA — pra funcionar tanto
                // com 1 intervalo (compat) quanto com almoço/jantar separados.
                LocalTime corte = ativo.fechamento.minusMinutes(cutoff);
                e.dentroCutoff = estaDentro(agoraLT, corte, ativo.fechamento);
            }
        }
        return e;
    }

    /**
     * Extrai a lista de intervalos do map do dia. Aceita:
     *  - Formato NOVO: {@code {aberto, intervalos: [{abertura,fechamento}, ...]}}
     *  - Formato ANTIGO: {@code {aberto, abertura, fechamento}} → 1 intervalo
     *  - Ambos formatos coexistindo: prioriza {@code intervalos} se tiver ≥1
     *    janela válida (a chave antiga vira o "padrão de exibição" mas ignorada).
     *
     * Intervalos com abertura/fechamento inválidos são silenciosamente descartados.
     */
    @SuppressWarnings("unchecked")
    public static List<Intervalo> extrairIntervalos(Map<String, ?> diaMap) {
        List<Intervalo> out = new ArrayList<>();
        if (diaMap == null) return out;
        Object raw = diaMap.get("intervalos");
        if (raw instanceof List<?> lst && !lst.isEmpty()) {
            for (Object it : lst) {
                if (!(it instanceof Map<?, ?> m)) continue;
                Map<String, Object> mm = (Map<String, Object>) m;
                LocalTime a = parseHora(mm.get("abertura"));
                LocalTime f = parseHora(mm.get("fechamento"));
                if (a != null && f != null) out.add(new Intervalo(a, f));
            }
            if (!out.isEmpty()) return out;
        }
        // Fallback ANTIGO — abertura/fechamento no root
        LocalTime a = parseHora(diaMap.get("abertura"));
        LocalTime f = parseHora(diaMap.get("fechamento"));
        if (a != null && f != null) out.add(new Intervalo(a, f));
        return out;
    }

    /** Pega o objeto do dia atual do horariosJson. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> horarioDoDia(Restaurante r, LocalDate hoje) {
        if (r.getHorariosJson() == null || r.getHorariosJson().isBlank()) return null;
        try {
            Map<String, Object> all = JSON.readValue(r.getHorariosJson(), Map.class);
            String chave = chaveDoDia(hoje.getDayOfWeek());
            Object dia = all.get(chave);
            if (dia instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (Exception ignore) {}
        return null;
    }

    private String chaveDoDia(DayOfWeek d) {
        return switch (d) {
            case MONDAY -> "seg";
            case TUESDAY -> "ter";
            case WEDNESDAY -> "qua";
            case THURSDAY -> "qui";
            case FRIDAY -> "sex";
            case SATURDAY -> "sab";
            case SUNDAY -> "dom";
        };
    }

    private static LocalTime parseHora(Object o) {
        if (o == null) return null;
        try { return LocalTime.parse(o.toString().trim()); }
        catch (Exception e) { return null; }
    }

    /**
     * Verifica se "agora" está no intervalo [inicio, fim].
     * Trata madrugada: se fim <= inicio, o intervalo cruza meia-noite (ex 22:00–02:00).
     */
    private boolean estaDentro(LocalTime agora, LocalTime inicio, LocalTime fim) {
        if (fim.isAfter(inicio) || fim.equals(inicio)) {
            // intervalo "normal" — ex: 11:00 → 22:00
            return !agora.isBefore(inicio) && agora.isBefore(fim);
        }
        // cruza madrugada — ex: 22:00 → 02:00 (fim do "dia" no início da manhã)
        return !agora.isBefore(inicio) || agora.isBefore(fim);
    }

    public ZoneId getZone() { return TZ; }
}
