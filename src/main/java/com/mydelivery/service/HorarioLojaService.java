package com.mydelivery.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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
 */
@Service
public class HorarioLojaService {

    private static final ZoneId TZ = ZoneId.of("America/Sao_Paulo");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Resultado consolidado pro caller decidir o que fazer. */
    public static class EstadoHorario {
        public boolean horarioConfigurado;  // tem horario pra hoje cadastrado?
        public boolean dentroHorario;       // hora atual ∈ [abertura, fechamento]
        public boolean dentroCutoff;        // estamos nos N min antes do fechamento?
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

        LocalTime ab = parseHora(hoje.get("abertura"));
        LocalTime fc = parseHora(hoje.get("fechamento"));
        if (ab == null || fc == null) return e;

        e.horarioConfigurado = true;
        e.abertura = ab;
        e.fechamento = fc;
        e.dentroHorario = estaDentro(agora.toLocalTime(), ab, fc);
        if (e.dentroHorario && Boolean.TRUE.equals(r.getPararPedidosAntesFechamento())) {
            int cutoff = r.getMinutosAntesFechamento() != null ? r.getMinutosAntesFechamento() : 0;
            if (cutoff > 0) {
                // Faltam <= cutoff minutos pro fechamento? Bloqueia pedidos.
                LocalTime corte = fc.minusMinutes(cutoff);
                // Se cruza madrugada, "agora" pode estar no dia seguinte do fechamento
                // mas mesmo assim a comparação por LocalTime funciona com o helper abaixo.
                e.dentroCutoff = estaDentro(agora.toLocalTime(), corte, fc);
            }
        }
        return e;
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

    private LocalTime parseHora(Object o) {
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
