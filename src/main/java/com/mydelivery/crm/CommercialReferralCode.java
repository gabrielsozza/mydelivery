package com.mydelivery.crm;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Valida códigos emitidos pelo painel comercial com o segredo interno compartilhado. */
public final class CommercialReferralCode {
    private static final Pattern FORMAT=Pattern.compile("c([0-9a-z]+)-([0-9a-f]{24})");
    private static final Pattern LEAD_FORMAT=Pattern.compile("c([0-9a-z]+)-l([0-9a-z]+)-([0-9a-f]{24})");
    private CommercialReferralCode() {}

    public record Referral(long collaboratorId, Long leadId) {}

    public static long verify(String code,String secret) {
        return parse(code,secret).collaboratorId();
    }

    public static Referral parse(String code,String secret) {
        if(secret==null||secret.isBlank())throw new IllegalStateException("Origem comercial indisponível.");
        String suppliedCode=code==null?"":code.trim();
        var leadMatch=LEAD_FORMAT.matcher(suppliedCode);
        boolean hasLead=leadMatch.matches();
        var match=hasLead?leadMatch:FORMAT.matcher(suppliedCode);
        if(!hasLead&&!match.matches())throw new IllegalArgumentException("Link do colaborador inválido.");
        try {
            String id=match.group(1);
            long collaboratorId=Long.parseLong(id,36);
            if(collaboratorId<=0)throw new IllegalArgumentException("Link do colaborador inválido.");
            Long leadId=hasLead?Long.parseLong(match.group(2),36):null;
            if(hasLead&&leadId<=0)throw new IllegalArgumentException("Link do colaborador inválido.");
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String payload=hasLead?"colaborador:"+id+":lead:"+match.group(2):"colaborador:"+id;
            byte[] expected=mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] supplied=HexFormat.of().parseHex(match.group(hasLead?3:2));
            if(!MessageDigest.isEqual(java.util.Arrays.copyOf(expected,12),supplied))
                throw new IllegalArgumentException("Link do colaborador inválido.");
            return new Referral(collaboratorId,leadId);
        } catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalStateException("Não foi possível validar a origem comercial.",e);}
    }
}
