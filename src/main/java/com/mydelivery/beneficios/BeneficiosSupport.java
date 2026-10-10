package com.mydelivery.beneficios;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class BeneficiosSupport {
    private BeneficiosSupport() {}
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    public static BigDecimal money(Object n) { return n == null ? new BigDecimal("0.00") : new BigDecimal(n.toString()).setScale(2,RoundingMode.HALF_UP); }
    public static String text(Object o) { return o == null ? "" : o.toString().trim(); }
    public static boolean yes(Object o) { return Boolean.TRUE.equals(o) || o instanceof Number n && n.intValue()!=0; }
    public static long id(Object o) { return ((Number)o).longValue(); }
    public static String phone(String phone) {
        String p=text(phone).replaceAll("\\D","");
        if(p.startsWith("55") && (p.length()==12 || p.length()==13)) p=p.substring(2);
        if(!p.matches("[1-9][0-9]{9,10}")) throw bad("Informe um telefone válido com DDD.");
        return p;
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
