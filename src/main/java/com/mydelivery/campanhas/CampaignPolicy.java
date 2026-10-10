package com.mydelivery.campanhas;

import java.text.Normalizer;
import java.time.*;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

/** Tetos operacionais próprios. Não são limites oficiais nem proteção contra banimento. */
@Component @ConfigurationProperties(prefix="mydelivery.campanhas") @Data
public class CampaignPolicy {
    private int maxHora=30, maxDia=200, minIntervaloSegundos=30, maxIntervaloSegundos=90, minDiasCliente=3,
        max30Dias=4, maxTentativas=3, backoffSegundos=300, leaseSegundos=120,
        sinaisParaPausa=3, janelaSinaisHoras=24;
    public static final ZoneId ZONE=ZoneId.of("America/Sao_Paulo");
    /** Rodízio determinístico de espaçamento, sem simular atividade humana. */
    public int intervalForStep(long step) {
        int low=Math.max(30,minIntervaloSegundos), high=Math.max(low,Math.min(90,maxIntervaloSegundos));
        return low+(int)Math.floorMod(step,3)*(high-low)/2;
    }
    public static String phone(String raw) {
        if(raw==null || raw.contains("@g.us") || raw.contains("@lid")) return null;
        String p=raw.split("@")[0].replaceAll("\\D","");
        if((p.length()==12||p.length()==13)&&p.startsWith("55")) p=p.substring(2);
        return p.matches("[1-9][0-9]{9,10}") ? p : null;
    }
    public static String normalized(String text) {
        return Normalizer.normalize(Objects.toString(text,""),Normalizer.Form.NFD)
            .replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).trim();
    }
    public static boolean optOut(String text) {
        String s=normalized(text).replaceAll("[.!?]+$","").trim();
        return Set.of("pare","parar","stop","sair","remover","saia").contains(s)
            || s.matches("(?s).*(nao quero (mais )?receber|me remov[ae]|me tir[ae] (da|dessa|desta) lista|nao (me )?(mande|envie|mandem|enviem)( mais)? (mensage|promo|oferta)|remov[ae]m? (meu numero|me|o meu)|tir[ae]m? (meu numero|me|da lista)|sair da lista|parar (de enviar|promo)|cancelar promo).*");
    }
    public static boolean negative(String text) {
        return normalized(text).matches("(?s).*(nao tenho interesse|nao estou interessado|nao estou interessada|nao me interessa|isso e spam|vou denunciar).*");
    }
    public static boolean possibleHuman(String text, long seconds, boolean byApi) {
        String s=normalized(text);
        return !byApi && seconds>=2 && !s.isBlank()
            && !s.matches("(?s).*(mensagem automatica|resposta automatica|agradecemos (o|seu) contato|em breve (retornaremos|responderemos)|nosso horario de atendimento|fora do horario|digite [0-9]).*");
    }
    public static String message(String name,String text) {
        return (name==null||name.isBlank()?"Olá!":"Olá "+name.trim().split("\\s+")[0]+",")+"\n\n"+text.trim();
    }
    public static boolean inWindow(LocalTime now, String start,String end) {
        return !now.isBefore(LocalTime.parse(start)) && now.isBefore(LocalTime.parse(end));
    }
}
