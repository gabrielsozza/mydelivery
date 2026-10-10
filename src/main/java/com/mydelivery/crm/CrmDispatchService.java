package com.mydelivery.crm;

import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.UazapiClient;
import com.mydelivery.service.whatsapp.WhatsappService;
import com.mydelivery.service.whatsapp.EvolutionCircuitOpenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.mydelivery.crm.CrmStore.*;
import static com.mydelivery.crm.CrmIdentity.*;

@Service
@RequiredArgsConstructor
public class CrmDispatchService {
    private final CrmStore db;
    private final CrmService crm;
    private final WhatsappInstanceRepository instances;
    private final UazapiClient provider;
    private final WhatsappService whatsapp;
    private final CrmOutboundPolicy outboundPolicy;

    @Transactional
    public Map<String,Object> enqueue(long id,String kind,String requestKey,long version,String actor) {
        outboundPolicy.requireSystemSendAllowed();
        db.lock();var lead=db.lead(id);
        if(requestKey==null||!requestKey.matches("[a-zA-Z0-9_-]{16,80}")) throw new IllegalArgumentException("Identificador de envio inválido.");
        var prior=db.jdbc().queryForList("SELECT * FROM crm_outbox WHERE request_key=?",requestKey);
        if(!prior.isEmpty()) {
            if(number(prior.getFirst().get("lead_id"))!=id) throw new IllegalArgumentException("Identificador de envio já utilizado.");
            return db.dto(prior.getFirst());
        }
        if(number(lead.get("version"))!=version) throw new IllegalArgumentException("Lead alterado. Atualize e revise a mensagem antes de enviar.");
        boolean initial="INITIAL".equals(kind);
        if(!initial&&!"MANUAL".equals(kind)) throw new IllegalArgumentException("Tipo de envio inválido.");
        if(initial && !"CONTATO_PREPARADO".equals(lead.get("stage"))) throw new IllegalArgumentException("Prepare o contato antes de enviar.");
        if(initial && (!"AUTOMATICO".equals(lead.get("handoff")) || lead.get("sent_at")!=null)) throw new IllegalArgumentException("Contato inicial já realizado ou atendimento humano ativo.");
        if(!initial && !"EM_ATENDIMENTO_HUMANO".equals(lead.get("handoff"))) throw new IllegalArgumentException("Assuma o atendimento para enviar uma resposta manual.");
        var p=db.profile(lead);String phone=phone(text(p.get("whatsapp")).isBlank()?p.get("telefone"):p.get("whatsapp"));
        if(phone.isBlank()) throw new IllegalArgumentException("Informe o WhatsApp do lead.");
        if(bool(lead.get("do_not_contact"))||crm.blockedPhone(phone)) throw new IllegalArgumentException("NÃO CONTATAR: envio bloqueado permanentemente.");
        if(Set.of("CLIENTE","PERDIDO").contains(text(lead.get("stage")))) throw new IllegalArgumentException("Lead encerrado. Nenhum contato comercial será enviado.");
        String body=text(lead.get("prepared_message"));CrmService.validateMessage(body);
        String contentKey=hash(id+"|"+phoneKey(phone)+"|"+body);
        if(db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_outbox WHERE content_key=? OR (? IS NOT NULL AND initial_key=?)",Long.class,contentKey,initial?phoneKey(phone):null,phoneKey(phone))>0)
            throw new IllegalArgumentException("Abordagem já registrada. Consulte o histórico para evitar contato repetido.");
        if(db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_outbox WHERE lead_id=? AND status IN ('QUEUED','SENDING','UNCERTAIN')",Long.class,id)>0)
            throw new IllegalArgumentException("Há um envio pendente ou incerto para este lead. Confira antes de enviar outro.");
        long queueId=db.insert("INSERT INTO crm_outbox(lead_id,phone,phone_key,body,kind,request_key,content_key,initial_key,created_at) VALUES (?,?,?,?,?,?,?,?,?)",id,phone,phoneKey(phone),body,kind,requestKey,contentKey,initial?phoneKey(phone):null,db.now());
        db.event(id,"ENVIO_AGENDADO",Map.of("envioId",queueId,"mensagem",body,"tipo",kind),actor);
        return db.dto(db.jdbc().queryForMap("SELECT * FROM crm_outbox WHERE id=?",queueId));
    }
    @Transactional
    public Long claim() {
        var config=db.lock();var now=db.now();
        // A crash after the provider call cannot be retried safely without provider idempotency.
        var abandoned=db.jdbc().queryForList("SELECT id,lead_id FROM crm_outbox WHERE status='SENDING' AND attempted_at<?",now.minusMinutes(2));
        for(var row:abandoned) {
            db.jdbc().update("UPDATE crm_outbox SET status='UNCERTAIN',error_detail='Processo interrompido: confira no WhatsApp antes de resolver.' WHERE id=?",row.get("id"));
            db.event(number(row.get("lead_id")),"ENVIO_INCERTO",Map.of("envioId",row.get("id")),"sistema");
        }
        if(outboundPolicy.manualOnly()) {
            db.jdbc().update("UPDATE crm_outbox SET status='CANCELLED',finished_at=?,error_detail='Envio pelo sistema pausado; contato manual necessário.' WHERE status='QUEUED'",now);
            return null;
        }
        if(!bool(config.get("enabled"))||!inWindow(now.toLocalTime(),text(config.get("start_time")),text(config.get("end_time")))) return null;
        LocalDateTime last=time(config.get("last_attempt_at"));
        if(last!=null&&last.plusSeconds(number(config.get("interval_seconds"))).isAfter(now)) return null;
        if(provider.circuitBreakerAberto()) return null;
        var instance=instances.findByRestauranteId(ProspectingPolicy.STORE_ID).orElse(null);
        if(!ready(instance)) return null;
        long daily=db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_outbox WHERE attempted_at>=?",Long.class,now.toLocalDate().atStartOfDay());
        long hourly=db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_outbox WHERE attempted_at>=?",Long.class,now.minusHours(1));
        if(daily>=number(config.get("daily_limit"))||hourly>=number(config.get("hourly_limit"))) return null;
        var rows=db.jdbc().queryForList("SELECT * FROM crm_outbox WHERE status='QUEUED' ORDER BY id LIMIT 1");if(rows.isEmpty()) return null;
        long id=number(rows.getFirst().get("id"));
        db.jdbc().update("UPDATE crm_outbox SET status='SENDING',attempted_at=? WHERE id=?",now,id);
        db.jdbc().update("UPDATE crm_settings SET last_attempt_at=? WHERE id=1",now);
        return id;
    }
    static boolean inWindow(LocalTime time,String start,String end) { return !time.isBefore(LocalTime.parse(start))&&time.isBefore(LocalTime.parse(end)); }
    static boolean ready(WhatsappInstance instance) { return ProspectingPolicy.isInstance(instance)&&instance.getStatus()==WhatsappInstance.Status.CONECTADA&&!Boolean.TRUE.equals(instance.getDesconectadoManualmente()); }
    @Transactional
    public void deliver(long queueId) {
        var settings=db.lock();var q=db.jdbc().queryForMap("SELECT * FROM crm_outbox WHERE id=?",queueId);
        if(!"SENDING".equals(q.get("status"))) return;
        if(outboundPolicy.manualOnly()) {
            db.jdbc().update("UPDATE crm_outbox SET status='UNCERTAIN',finished_at=?,error_detail='Pausa de segurança: confira no WhatsApp antes de registrar ou repetir.' WHERE id=?",db.now(),queueId);
            return;
        }
        long leadId=number(q.get("lead_id"));var lead=db.lead(leadId);String phone=text(q.get("phone"));
        boolean initial="INITIAL".equals(q.get("kind"));
        if(bool(lead.get("do_not_contact"))||crm.blockedPhone(phone)||Set.of("CLIENTE","PERDIDO").contains(text(lead.get("stage")))
            ||initial&&!"AUTOMATICO".equals(lead.get("handoff"))||!initial&&!"EM_ATENDIMENTO_HUMANO".equals(lead.get("handoff"))) {
            db.jdbc().update("UPDATE crm_outbox SET status='CANCELLED',finished_at=?,error_detail='Opt-out, encerramento ou controle humano alterado.' WHERE id=?",db.now(),queueId);return;
        }
        var inst=instances.findByRestauranteId(ProspectingPolicy.STORE_ID).orElse(null);
        if(!bool(settings.get("enabled"))||!ready(inst)||provider.circuitBreakerAberto()||!inWindow(db.now().toLocalTime(),text(settings.get("start_time")),text(settings.get("end_time")))) { requeue(queueId);return; }
        ProspectingPolicy.requireInstance(inst);
        db.jdbc().update("UPDATE crm_leads SET attempts=attempts+1 WHERE id=?",leadId);
        db.event(leadId,"TENTATIVA_ENVIO",Map.of("envioId",queueId),"sistema");
        try {
            // Reuses the existing transport and instance token, without simulated typing or proxy changes.
            var response=provider.enviarTexto(inst.getInstanceName(),inst.getInstanceToken(),phone,text(q.get("body")),0);
            if(response==null||response.isEmpty()||Boolean.FALSE.equals(response.get("success"))||Boolean.FALSE.equals(response.get("ok"))||response.get("error")!=null)
                throw new IllegalStateException("Resposta do provedor sem confirmação.");
            String providerId=text(response.getOrDefault("messageid",response.getOrDefault("id","")));
            db.jdbc().update("UPDATE crm_outbox SET status='SENT',provider_id=?,finished_at=? WHERE id=?",providerId.substring(0,Math.min(200,providerId.length())),db.now(),queueId);
            markSent(lead,q,"sistema");

        } catch(EvolutionCircuitOpenException e) { requeue(queueId); }
        catch(RuntimeException e) {
            db.jdbc().update("UPDATE crm_outbox SET status='UNCERTAIN',error_detail='Provedor sem confirmação. Confira a conversa; não haverá reenvio automático.',finished_at=? WHERE id=?",db.now(),queueId);
            db.event(leadId,"ENVIO_INCERTO",Map.of("envioId",queueId,"motivo","Falha ou timeout do provedor; conferir manualmente"),"sistema");
        }
    }
    /** Called by the job only after deliver has committed; heartbeat failure cannot roll back delivery. */
    public void refreshHeartbeat(long queueId) {
        try {
            if("SENT".equals(db.jdbc().queryForMap("SELECT status FROM crm_outbox WHERE id=?",queueId).get("status")))
                instances.findByRestauranteId(ProspectingPolicy.STORE_ID).filter(CrmDispatchService::ready).ifPresent(whatsapp::marcarRespostaEnviada);
        } catch(RuntimeException ignored) { /* existing scheduled health checks remain responsible for recovery */ }
    }
    private void requeue(long id) { db.jdbc().update("UPDATE crm_outbox SET status='QUEUED',attempted_at=NULL WHERE id=?",id); }
    private void markSent(Map<String,Object> lead,Map<String,Object> q,String actor) {
        long leadId=number(lead.get("id"));
        db.jdbc().update("UPDATE crm_leads SET sent_message=?,sent_at=?,last_interaction_at=?,version=version+1 WHERE id=?",q.get("body"),db.now(),db.now(),leadId);
        db.event(leadId,"MENSAGEM_ENVIADA",Map.of("envioId",q.get("id"),"mensagem",q.get("body"),"aceiteProvedor",true),actor);
        if(lead.get("sent_at")==null) db.event(leadId,"PRIMEIRO_CONTATO",Map.of("envioId",q.get("id")),actor);
        if("CONTATO_PREPARADO".equals(lead.get("stage"))) crm.move(leadId,CrmService.Stage.CONTATADO,"Envio aceito pelo provedor",actor);
    }
    @Transactional
    public void cancel(long leadId,long id,String actor) {
        db.lock();db.lead(leadId);
        if(db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_outbox WHERE id=? AND lead_id=? AND status='QUEUED'",Long.class,id,leadId)!=1) throw new IllegalArgumentException("Somente envios na fila podem ser cancelados.");
        crm.releaseUnsentKeys(id);int changed=db.jdbc().update("UPDATE crm_outbox SET status='CANCELLED',finished_at=?,error_detail='Cancelado pelo admin' WHERE id=? AND lead_id=? AND status='QUEUED'",db.now(),id,leadId);
        if(changed!=1) throw new IllegalArgumentException("Somente envios na fila podem ser cancelados.");db.event(leadId,"ENVIO_CANCELADO",Map.of("envioId",id),actor);
    }
    @Transactional
    public void resolve(long leadId,long id,boolean wasSent,String reason,String actor) {
        db.lock();CrmService.validateMessage(reason);var q=db.jdbc().queryForMap("SELECT * FROM crm_outbox WHERE id=? AND lead_id=?",id,leadId);
        if(!"UNCERTAIN".equals(q.get("status"))) throw new IllegalArgumentException("Envio não está incerto.");
        db.jdbc().update("UPDATE crm_outbox SET status=?,finished_at=?,error_detail=? WHERE id=?",wasSent?"SENT":"CANCELLED",db.now(),"Conferido manualmente",id);
        if(wasSent) markSent(db.lead(leadId),q,actor);
        db.event(leadId,"ENVIO_CONFERIDO",Map.of("envioId",id,"enviado",wasSent,"evidencia",reason),actor);
    }
}
