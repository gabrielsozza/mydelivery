package com.mydelivery.crm;

import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.UazapiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.*;
import static com.mydelivery.crm.CrmIdentity.*;
import static com.mydelivery.crm.CrmStore.*;

/** Commercial inbox backed by the existing CRM interactions and current lead assignment. */
@Service @RequiredArgsConstructor
public class CrmCommercialWhatsappService {
    private static final List<String> MESSAGE_TYPES=List.of("MENSAGEM_COMERCIAL","MENSAGEM_ENVIADA","MENSAGEM_HUMANA","RESPOSTA_RECEBIDA","RESPOSTA_DESCONSIDERADA");
    private static final String INVALID_RECIPIENT_MESSAGE="O provedor não reconheceu o destinatário e recusou o envio. Confirme se o WhatsApp do lead é o mesmo número exibido neste modal. Se for, a conexão comercial precisa ser verificada.";
    private final CrmStore db;
    private final WhatsappInstanceRepository instances;
    private final UazapiClient provider;
    private final CrmOutboundPolicy outboundPolicy;

    public Map<String,Object> connection() {
        var inst=instances.findByRestauranteId(ProspectingPolicy.STORE_ID).orElse(null);
        return Map.of("connected",CrmDispatchService.ready(inst),"phone",inst==null||inst.getPhone()==null?"":inst.getPhone(),"manualOnly",outboundPolicy.manualOnly());
    }

    private Map<String,Object> owned(long id,long collaboratorId) {
        var lead=db.lead(id);
        if(number(lead.get("assigned_collaborator_id"))!=collaboratorId)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Conversa não disponível para sua carteira.");
        return lead;
    }

    public Map<String,Object> inbox(long collaboratorId,String filter,String search) {
        String selected=text(filter).toUpperCase(Locale.ROOT);
        if(!selected.isBlank()&&!selected.equals("UNREAD")) {
            try { CrmService.Stage.valueOf(selected); }
            catch(IllegalArgumentException e) { throw new IllegalArgumentException("Etapa do CRM inválida."); }
        }
        String query="SELECT l.id,l.profile,l.stage,l.version,l.do_not_contact,l.assigned_collaborator_id,"
                +"latest.id last_id,latest.detail last_detail,latest.type last_type,latest.created_at last_at,"
                +"(SELECT COUNT(*) FROM crm_interactions incoming WHERE incoming.lead_id=l.id "
                +"AND incoming.type IN ('RESPOSTA_RECEBIDA','RESPOSTA_DESCONSIDERADA') "
                +"AND incoming.id>COALESCE(read_state.last_read_interaction_id,0)) unread "
                +"FROM crm_leads l JOIN crm_interactions latest ON latest.id=(SELECT MAX(i.id) FROM crm_interactions i "
                +"WHERE i.lead_id=l.id AND i.type IN ('MENSAGEM_COMERCIAL','MENSAGEM_ENVIADA','MENSAGEM_HUMANA','RESPOSTA_RECEBIDA','RESPOSTA_DESCONSIDERADA')) "
                +"LEFT JOIN crm_commercial_read_state read_state ON read_state.lead_id=l.id "
                +"WHERE l.assigned_collaborator_id=? ORDER BY latest.id DESC";
        List<Map<String,Object>> rows=new ArrayList<>();
        Map<String,Integer> counts=new LinkedHashMap<>();
        int unreadCount=0;
        String needle=text(search).toLowerCase(Locale.ROOT);
        for(var row:db.jdbc().queryForList(query,collaboratorId)) {
            var profile=db.profile(row);
            String name=text(profile.get("nome"));
            String phone=text(profile.get("whatsapp")).isBlank()?text(profile.get("telefone")):text(profile.get("whatsapp"));
            if(!needle.isBlank()&&!name.toLowerCase(Locale.ROOT).contains(needle)&&!phone.contains(needle)) continue;
            String stage=text(row.get("stage"));
            counts.merge(stage,1,Integer::sum);
            int unread=((Number)row.get("unread")).intValue();
            if(unread>0)unreadCount++;
            if(selected.equals("UNREAD")&&unread==0||!selected.isBlank()&&!selected.equals("UNREAD")&&!selected.equals(stage))continue;
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("leadId",row.get("id"));item.put("name",name);item.put("phone",phone);
            item.put("stage",stage);item.put("version",row.get("version"));item.put("unread",unread);
            item.put("lastAt",date(row.get("last_at")));
            item.put("lastText",messageText(row.get("last_detail"),text(row.get("last_type"))));
            rows.add(item);
        }
        return Map.of("stages",Arrays.stream(CrmService.Stage.values()).map(Enum::name).toList(),
                "counts",counts,"total",counts.values().stream().mapToInt(Integer::intValue).sum(),"unread",unreadCount,"conversations",rows);
    }

    public Map<String,Object> conversation(long collaboratorId,long leadId,long before) {
        var lead=owned(leadId,collaboratorId);
        String sql="SELECT id,type,detail,actor,created_at FROM crm_interactions WHERE lead_id=? "
                +"AND type IN ('MENSAGEM_COMERCIAL','MENSAGEM_ENVIADA','MENSAGEM_HUMANA','RESPOSTA_RECEBIDA','RESPOSTA_DESCONSIDERADA') "
                +(before>0?"AND id<? ":"")+"ORDER BY id DESC LIMIT 100";
        Object[] args=before>0?new Object[]{leadId,before}:new Object[]{leadId};
        var rows=db.jdbc().queryForList(sql,args);
        List<Map<String,Object>> messages=new ArrayList<>();
        for(var row:rows) {
            String type=text(row.get("type"));
            messages.add(Map.of("id",row.get("id"),"direction",type.startsWith("RESPOSTA_")?"IN":"OUT",
                    "text",messageText(row.get("detail"),type),"at",date(row.get("created_at")),"actor",text(row.get("actor"))));
        }
        Collections.reverse(messages);
        var p=db.profile(lead);
        Map<String,Object> info=new LinkedHashMap<>();
        info.put("leadId",leadId);info.put("name",text(p.get("nome")));
        info.put("phone",text(p.get("whatsapp")).isBlank()?text(p.get("telefone")):text(p.get("whatsapp")));
        info.put("city",text(p.get("cidade")));info.put("state",text(p.get("estado")));
        info.put("notes",text(p.get("observacoes")));info.put("stage",lead.get("stage"));
        info.put("version",lead.get("version"));info.put("doNotContact",bool(lead.get("do_not_contact")));
        info.put("messages",messages);info.put("hasMore",rows.size()==100);
        return info;
    }

    public Map<String,Object> read(long collaboratorId,long leadId) {
        owned(leadId,collaboratorId);
        Long last=db.jdbc().queryForObject("SELECT COALESCE(MAX(id),0) FROM crm_interactions WHERE lead_id=? AND type IN ('RESPOSTA_RECEBIDA','RESPOSTA_DESCONSIDERADA')",Long.class,leadId);
        db.jdbc().update("INSERT INTO crm_commercial_read_state(lead_id,last_read_interaction_id,updated_at) VALUES (?,?,?) "
                +"ON DUPLICATE KEY UPDATE last_read_interaction_id=GREATEST(last_read_interaction_id,VALUES(last_read_interaction_id)),updated_at=VALUES(updated_at)",leadId,last,db.now());
        return Map.of("ok",true);
    }

    @Transactional
    public Map<String,Object> send(long collaboratorId,long leadId,String body,String requestKey) {
        return send(collaboratorId,leadId,body,requestKey,collaboratorId==0?"admin":"comercial:"+collaboratorId);
    }

    @Transactional
    public Map<String,Object> send(long collaboratorId,long leadId,String body,String requestKey,String actor) {
        // O bloqueio de disparos automáticos permanece; este endpoint exige texto
        // escrito e envio explícito por um colaborador para um lead da sua carteira.
        var lead=collaboratorId==0?db.lead(leadId):owned(leadId,collaboratorId);
        db.jdbc().queryForMap("SELECT id FROM crm_leads WHERE id=? FOR UPDATE",leadId);
        lead=collaboratorId==0?db.lead(leadId):owned(leadId,collaboratorId);
        String message=text(body);
        if(message.isBlank()||message.length()>4000)throw new IllegalArgumentException("Mensagem deve ter de 1 a 4000 caracteres.");
        if(!text(requestKey).matches("[a-zA-Z0-9_-]{16,80}"))throw new IllegalArgumentException("Identificador de envio inválido.");
        var p=db.profile(lead);
        String phone=phone(text(p.get("whatsapp")).isBlank()?p.get("telefone"):p.get("whatsapp"));
        if(phone.isBlank())throw new IllegalArgumentException("Informe o WhatsApp do lead.");
        if(bool(lead.get("do_not_contact"))||db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_do_not_contact WHERE phone_key=?",Long.class,phoneKey(phone))>0)
            throw new IllegalArgumentException("Este contato não pode receber mensagens.");
        if(Set.of("CLIENTE","PERDIDO").contains(text(lead.get("stage"))))throw new IllegalArgumentException("Lead encerrado no CRM.");
        var prior=db.jdbc().queryForList("SELECT lead_id,collaborator_id,status FROM crm_commercial_sends WHERE request_key=?",requestKey);
        if(!prior.isEmpty()) {
            var existing=prior.getFirst();
            if(number(existing.get("lead_id"))!=leadId||number(existing.get("collaborator_id"))!=collaboratorId)throw new IllegalArgumentException("Identificador já utilizado.");
            String status=text(existing.get("status"));
            return "FAILED".equals(status)?Map.of("status",status,"requestKey",requestKey,"message",INVALID_RECIPIENT_MESSAGE)
                    :Map.of("status",status,"requestKey",requestKey);
        }
        var inst=instances.findByRestauranteId(ProspectingPolicy.STORE_ID).orElseThrow(()->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"WhatsApp comercial não configurado."));
        ProspectingPolicy.requireInstance(inst);
        if(!CrmDispatchService.ready(inst))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"WhatsApp comercial desconectado.");
        db.jdbc().update("INSERT INTO crm_commercial_sends(request_key,lead_id,collaborator_id,body,status,created_at) VALUES (?,?,?,?,?,?)",requestKey,leadId,collaboratorId,message,"SENDING",db.now());
        Map<String,Object> response;
        try {
            response=provider.enviarTexto(inst.getInstanceName(),inst.getInstanceToken(),phone,message,0);
            if(response==null||response.isEmpty()||Boolean.FALSE.equals(response.get("success"))||Boolean.FALSE.equals(response.get("ok"))||response.get("error")!=null)
                throw new IllegalStateException("Resposta do provedor sem confirmação: "+text(response));
        } catch(RuntimeException e) {
            if(invalidRecipient(e)) {
                db.jdbc().update("UPDATE crm_commercial_sends SET status='FAILED',finished_at=? WHERE request_key=?",db.now(),requestKey);
                return Map.of("status","FAILED","requestKey",requestKey,"message",INVALID_RECIPIENT_MESSAGE);
            }
            db.jdbc().update("UPDATE crm_commercial_sends SET status='UNCERTAIN',finished_at=? WHERE request_key=?",db.now(),requestKey);
            return Map.of("status","UNCERTAIN","requestKey",requestKey,"message","Não houve confirmação do WhatsApp. Confira a conversa antes de tentar novamente.");
        }
        String providerId=text(response.getOrDefault("messageid",response.getOrDefault("id","")));
        db.jdbc().update("UPDATE crm_commercial_sends SET status='SENT',provider_id=?,finished_at=? WHERE request_key=?",providerId.substring(0,Math.min(200,providerId.length())),db.now(),requestKey);
        db.jdbc().update("INSERT INTO crm_interactions(lead_id,type,detail,actor,created_at,dedup_key) VALUES (?,?,?,?,?,?)",leadId,"MENSAGEM_COMERCIAL",db.json(Map.of("texto",message,"messageId",providerId,"requestKey",requestKey)),text(actor).substring(0,Math.min(160,text(actor).length())),db.now(),hash("commercial:"+requestKey));
        db.jdbc().update("UPDATE crm_leads SET last_interaction_at=?,version=version+1 WHERE id=?",db.now(),leadId);
        return Map.of("status","SENT","requestKey",requestKey);
    }

    private static boolean invalidRecipient(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            String detail=cause instanceof RestClientResponseException response?response.getResponseBodyAsString():cause.getMessage();
            String normalized=text(detail).toLowerCase(Locale.ROOT);
            if(normalized.contains("not on whatsapp")||normalized.contains("not registered on whatsapp")
                    ||normalized.contains("não está no whatsapp")||normalized.contains("nao esta no whatsapp")) return true;
        }
        return false;
    }

    private String messageText(Object raw,String type) {
        try {
            Map<String,Object> detail=db.profile(Map.of("profile",raw));
            return text(detail.getOrDefault("texto",detail.getOrDefault("mensagem","[Mensagem sem texto]")));
        } catch(RuntimeException e) { return "[Mensagem indisponível]"; }
    }
    private static String date(Object value) { return value instanceof Timestamp t?t.toLocalDateTime().toString():text(value); }
}
