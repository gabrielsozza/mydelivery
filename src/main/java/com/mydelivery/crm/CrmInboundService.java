package com.mydelivery.crm;

import com.mydelivery.model.WhatsappInstance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import static com.mydelivery.crm.CrmIdentity.*;
import static com.mydelivery.crm.CrmStore.*;

@Service @RequiredArgsConstructor
public class CrmInboundService {
    private final CrmStore db;
    private final CrmService crm;
    private static final Pattern STOP=Pattern.compile("^(pare|parar|sair|stop|cancelar|remover)( por favor| obrigado)?$|nao (quero|desejo) (mais )?(receber|mensagens|contato)|nao (me )?(mande|envie|contate)|retire (meu|minha)|remov(a|er) (meu|minha)|tire (meu|minha)|me (tira|tire|retira|retire|remova|remove) (da|dessa|desta)|nao entre em contato");
    private static final Pattern INTEREST=Pattern.compile("(quero|gostaria de|pode|podemos|vamos) (uma )?(agendar|marcar|ver|conhecer|testar|contratar)|tenho interesse|quero saber (mais|o preco)|como (faco para )?(assinar|contratar)");
    public static boolean optOutText(String message) { return STOP.matcher(normalized(message)).find(); }
    public static boolean interestText(String message) {
        String n=normalized(message);
        return !n.matches(".*\\b(nao|sem|nunca)\\b.*")&&INTEREST.matcher(n).find();
    }
    /** Called synchronously before restaurant-only processing. False means completely untouched tenant. */
    @Transactional
    public boolean receive(WhatsappInstance inst,String remote,String messageId,String message,boolean fromMe,boolean sentByApi,Object timestamp) {
        if(!ProspectingPolicy.isInstance(inst)) return false;
        if(remote==null||(!remote.endsWith("@s.whatsapp.net")&&!remote.endsWith("@c.us"))) return true;
        if(sentByApi&&fromMe) return true;
        String number;try { number=phone(remote); } catch(IllegalArgumentException e) { return true; }
        message=text(message);
        if(message.length()>16000) message=message.substring(0,16000);
        db.lock();
        if(fromMe&&!text(messageId).isBlank()&&db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_outbox WHERE provider_id=?",Long.class,messageId)>0) return true;
        String dedup=hash(inst.getId()+"|"+(!text(messageId).isBlank()?messageId:remote+"|"+text(timestamp)+"|"+message+"|"+fromMe));
        if(db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_interactions WHERE dedup_key=?",Long.class,dedup)>0) return true;
        var match=db.jdbc().queryForList("SELECT lead_id FROM crm_identities WHERE identity_key=?","phone:"+phoneKey(number));
        long id;
        if(match.isEmpty()) {
            // Commercial inbound contacts stay in CRM; never create restaurant consumer records.
            if(fromMe) return true;
            var newLead=crm.create(new LinkedHashMap<>(Map.of("nome","Contato WhatsApp "+number,"whatsapp",number,"origem","WhatsApp recebido")),"whatsapp");id=number(newLead.get("id"));
        } else id=number(match.getFirst().get("lead_id"));
        var lead=db.lead(id);
        Long delay=fromMe?null:responseDelay(id,timestamp);
        boolean immediate=delay!=null&&delay<2000;
        String type=fromMe?"MENSAGEM_HUMANA":immediate?"RESPOSTA_DESCONSIDERADA":"RESPOSTA_RECEBIDA";
        var detail=new LinkedHashMap<String,Object>(Map.of("texto",text(message).isBlank()?"[Mensagem sem texto: conferir no WhatsApp]":message,"messageId",text(messageId),"timestamp",text(timestamp)));
        if(delay!=null) detail.put("tempoRespostaMs",delay);
        if(immediate) detail.put("motivoExclusao","Resposta em menos de 2 segundos; possível mensagem automática, excluída das métricas e do avanço automático.");
        db.jdbc().update("INSERT INTO crm_interactions(lead_id,type,detail,actor,created_at,dedup_key) VALUES (?,?,?,?,?,?)",id,type,db.json(detail),"whatsapp",db.now(),dedup);
        db.jdbc().update("UPDATE crm_leads SET last_interaction_at=?,version=version+1 WHERE id=?",db.now(),id);
        if(fromMe) { crm.handoff(id,"EM_ATENDIMENTO_HUMANO","whatsapp");return true; }
        if(optOutText(message)) { crm.blockNumber(number,message);crm.optOut(id,message,"whatsapp");return true; }
        if(bool(lead.get("do_not_contact"))) return true;
        if(immediate) return true;
        if("CONTATADO".equals(lead.get("stage"))) crm.move(id,CrmService.Stage.RESPONDEU,"Resposta recebida no WhatsApp","whatsapp");
        if(interestText(message)) {
            db.event(id,"INTERESSE_SUGERIDO",Map.of("razao","Expressão explícita de interesse; requer avaliação humana","trecho",text(message),"classificador","regras-conservadoras-v1"),"sistema");
            if(!"EM_ATENDIMENTO_HUMANO".equals(lead.get("handoff"))) crm.handoff(id,"AGUARDANDO_ATENDIMENTO_HUMANO","sistema");
        }
        // No automatic sales reply in this initial version: handoff is persistent until explicitly released.
        return true;
    }

    private Long responseDelay(long id,Object timestamp) {
        LocalDateTime received=db.now();
        boolean secondsPrecision=false;
        try {
            long value=Long.parseLong(text(timestamp));
            boolean seconds=value<100_000_000_000L;
            var providerTime=LocalDateTime.ofInstant(seconds?Instant.ofEpochSecond(value):Instant.ofEpochMilli(value),ZONE);
            if(providerTime.getYear()>=2000&&!providerTime.isAfter(received.plusMinutes(5))) {
                received=providerTime;secondsPrecision=seconds;
            }
        } catch(RuntimeException ignored) { /* Missing/invalid provider time: use webhook receipt time. */ }
        // Provider timestamps in seconds can precede the send's fractional second.
        var sent=db.jdbc().queryForList("SELECT created_at FROM crm_interactions WHERE lead_id=? AND type IN ('MENSAGEM_ENVIADA','MENSAGEM_HUMANA') AND created_at<=? ORDER BY created_at DESC,id DESC LIMIT 1",id,secondsPrecision?received.plusSeconds(1):received);
        if(sent.isEmpty()) return null;
        return Math.max(0,Duration.between(time(sent.getFirst().get("created_at")),received).toMillis());
    }
}
