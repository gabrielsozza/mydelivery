package com.mydelivery.campanhas;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClientResponseException;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.UazapiClient;
import com.mydelivery.model.WhatsappInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import static com.mydelivery.campanhas.CampaignService.*;

@Component @RequiredArgsConstructor @Slf4j
public class CampaignDispatcher {
    final CampaignService s;
    final WhatsappInstanceRepository instances;
    final UazapiClient transport;
    record Claim(long rid,long campaign,long destination,String phone,String text,String image,String instance,String sender){}

    @Scheduled(fixedDelay=5000,initialDelay=30000)
    public void tick(){
        for(var row:s.db.queryForList("SELECT DISTINCT restaurante_id FROM campanhas_crm WHERE status IN ('ENVIANDO','AGENDADA') UNION SELECT DISTINCT restaurante_id FROM campanha_destinos WHERE status='PROCESSANDO'")){
            long rid=num(row,"restaurante_id");
            try{Claim c=claim(rid);if(c!=null)send(c);}catch(Exception e){log.error("[Campanhas] Falha no processamento da loja {} ({})",rid,e.getClass().getSimpleName());}
        }
    }
    Claim claim(long rid){
        if(com.mydelivery.crm.ProspectingPolicy.isStore(rid))return null;
        return s.tx.execute(tx->{
            var cfg=s.config(rid,true);LocalDateTime n=now();
            // Perda de processo após chamada externa: nunca repetir automaticamente.
            int lost=s.db.update("UPDATE campanha_destinos SET status='INCERTO',motivo='Processamento interrompido; resultado não confirmado. Não reenviar.' WHERE restaurante_id=? AND status='PROCESSANDO' AND tentativa_em<?",rid,n.minusSeconds(s.policy.getLeaseSegundos()));
            if(lost>0){s.stop(rid,"Resultado de envio incerto após interrupção. Revisão necessária.",true);return null;}
            if(yes(cfg.get("bloqueado"))||!CampaignPolicy.inWindow(n.toLocalTime(),str(cfg,"inicio"),str(cfg,"fim")))return null;
            LocalDateTime next=time(cfg.get("proximo_envio"));if(next!=null&&next.isAfter(n))return null;
            if(s.db.queryForObject("SELECT COUNT(*) FROM campanha_destinos WHERE restaurante_id=? AND status='PROCESSANDO'",Long.class,rid)>0)return null;
            var campaigns=s.db.queryForList("SELECT * FROM campanhas_crm WHERE restaurante_id=? AND status IN ('ENVIANDO','AGENDADA') AND agendada_em<=? ORDER BY agendada_em,id LIMIT 1 FOR UPDATE",rid,n);
            if(campaigns.isEmpty())return null;var c=campaigns.getFirst();long cid=num(c,"id");
            var inst=s.db.queryForList("SELECT status,instance_name,phone FROM whatsapp_instances WHERE restaurante_id=?",rid);
            if(inst.isEmpty()||!"CONECTADA".equals(str(inst.getFirst(),"status"))){s.stop(rid,"WhatsApp desconectado. Reconecte o atendimento e revise a campanha antes de retomar.",false);return null;}
            var i=inst.getFirst();if(!Objects.equals(c.get("instance_name"),i.get("instance_name"))||!Objects.equals(c.get("instance_phone"),i.get("phone"))){s.stop(rid,"Número ou sessão alterado. Campanha interrompida; não continuar por outro canal.",true);return null;}
            // Tentativas consomem limite, inclusive falhas. Também contabiliza envios anteriores à migração.
            long hour=attempts(rid,n.minusHours(1)),day=attempts(rid,n.minusHours(24));
            if(hour>=Math.min(s.policy.getMaxHora(),num(cfg,"limite_hora"))||day>=Math.min(s.policy.getMaxDia(),num(cfg,"limite_dia")))return null;
            var queue=s.db.queryForList("SELECT * FROM campanha_destinos WHERE campanha_id=? AND status='PENDENTE' AND (proxima_tentativa IS NULL OR proxima_tentativa<=?) ORDER BY pedidos DESC,id LIMIT 1 FOR UPDATE",cid,n);
            if(queue.isEmpty()){
                if(s.db.queryForObject("SELECT COUNT(*) FROM campanha_destinos WHERE campanha_id=? AND status IN ('PENDENTE','PROCESSANDO')",Long.class,cid)==0)s.db.update("UPDATE campanhas_crm SET status='CONCLUIDA',concluida_em=? WHERE id=?",n,cid);
                return null;
            }
            var d=queue.getFirst();String phone=str(d,"telefone");
            CampaignService.Contact contact=s.base(rid).stream().filter(x->phone.equals(x.phone())).findFirst().orElse(null);
            String reason=contact==null?"Contato não pertence mais à base desta loja":s.exclusion(rid,contact,cfg);
            if(reason!=null){s.db.update("UPDATE campanha_destinos SET status='IGNORADO',motivo=? WHERE id=?",reason,num(d,"id"));return null;}
            s.db.update("UPDATE campanha_destinos SET status='PROCESSANDO',tentativas=tentativas+1,tentativa_em=? WHERE id=?",n,num(d,"id"));
            s.db.update("UPDATE campanha_controles SET proximo_envio=?,ritmo_passos=ritmo_passos+1 WHERE restaurante_id=?",n.plusSeconds(s.policy.intervalForStep(num(cfg,"ritmo_passos"))),rid);
            s.db.update("UPDATE campanhas_crm SET status='ENVIANDO' WHERE id=?",cid);
            s.audit(rid,"TENTATIVA","Destino "+num(d,"id"));
            return new Claim(rid,cid,num(d,"id"),phone,CampaignPolicy.message(str(d,"nome"),str(c,"texto")),str(c,"imagem_url"),str(i,"instance_name"),str(i,"phone"));
        });
    }
    long attempts(long rid,LocalDateTime since){
        return s.db.queryForObject("SELECT COUNT(*) FROM campanha_eventos WHERE restaurante_id=? AND tipo='TENTATIVA' AND criado_em>=?",Long.class,rid,since)
            +s.db.queryForObject("SELECT COUNT(*) FROM promo_campanha_envios e JOIN promo_campanhas c ON c.id=e.campanha_id WHERE c.restaurante_id=? AND e.enviado_em>=?",Long.class,rid,since);
    }
    void send(Claim c){
        try{
            WhatsappInstance i=instances.findByRestauranteId(c.rid()).orElseThrow();
            if(i.getStatus()!=WhatsappInstance.Status.CONECTADA||!Objects.equals(i.getInstanceName(),c.instance())||!Objects.equals(i.getPhone(),c.sender()))throw new IllegalStateException("Canal mudou antes do envio");
            if (!preflight(c, i)) return;
            boolean proceed=s.tx.execute(t->{
                var cfg=s.config(c.rid(),true);var campaign=s.owned(c.rid(),c.campaign(),true);
                if(yes(cfg.get("bloqueado"))||!"ENVIANDO".equals(str(campaign,"status"))||s.optedOut(c.rid(),c.phone())){
                    s.db.update("UPDATE campanha_destinos SET status=?,motivo='Envio impedido na verificação final' WHERE id=?",s.optedOut(c.rid(),c.phone())?"IGNORADO":"PENDENTE",c.destination());return false;
                }return true;
            });
            if(!proceed)return;
            // Sem delay de digitação, troca de sessão ou fallback. Uma única chamada externa.
            Map<String,Object> response=c.image().isBlank()?transport.enviarTexto(i.getInstanceName(),i.getInstanceToken(),"55"+c.phone(),c.text(),0):transport.enviarMidia(i.getInstanceName(),i.getInstanceToken(),"55"+c.phone(),c.image(),c.text(),0);
            if(response==null||(response.get("error")!=null&&!response.get("error").toString().isBlank()&&!Boolean.FALSE.equals(response.get("error")))||Boolean.FALSE.equals(response.get("success"))|| (response!=null&&Set.of("Failed","Canceled").contains(str(response,"status"))))throw new IllegalStateException("Resposta não confirma aceitação");
            String id=providerId(response);
            if(id==null)throw new IllegalStateException("Provedor não retornou identificador de mensagem");
            s.tx.executeWithoutResult(t->{s.config(c.rid(),true);s.db.update("UPDATE campanha_destinos SET status='ACEITO',aceito_em=?,provider_id=?,provider_internal_id=?,motivo=NULL WHERE id=? AND status='PROCESSANDO'",now(),id,response.get("id"),c.destination());});
        }catch(Exception e){failure(c,e);}
    }
    boolean preflight(Claim c, WhatsappInstance i) {
        Map<String,Object> limits;
        try { limits=transport.limitesCampanha(i.getInstanceName(),i.getInstanceToken()); }
        catch(Exception e) {
            RestClientResponseException http=null;
            for(Throwable x=e;x!=null;x=x.getCause())if(x instanceof RestClientResponseException h){http=h;break;}
            if(http!=null&&http.getStatusCode().value()==404){
                s.db.update("UPDATE campanha_controles SET diagnostico='Diagnóstico não disponível nesta versão do provedor',diagnostico_em=? WHERE restaurante_id=?",now(),c.rid());return true;
            }
            if(http!=null&&(http.getStatusCode().value()==401||http.getStatusCode().value()==403)){failure(c,e);return false;}
            s.tx.executeWithoutResult(t->{s.config(c.rid(),true);s.db.update("UPDATE campanha_destinos SET status='PENDENTE',motivo='Falha na consulta ao canal; nenhuma mensagem enviada' WHERE id=?",c.destination());s.stop(c.rid(),"Não foi possível consultar a condição do canal. Nenhuma mensagem enviada nesta tentativa.",false);});return false;
        }
        boolean restricted=restricted(limits);
        s.tx.executeWithoutResult(t->{s.config(c.rid(),true);
            s.db.update("UPDATE campanha_controles SET diagnostico=?,diagnostico_em=? WHERE restaurante_id=?",restricted?"Provedor indica restrição ativa":limits!=null&&Boolean.TRUE.equals(limits.get("can_send_new_messages"))?"Provedor não indicou restrição nesta consulta; não é garantia contra bloqueio":"Provedor não informou diagnóstico conclusivo",now(),c.rid());
            if(restricted){s.db.update("UPDATE campanha_destinos SET status='PENDENTE',motivo='Provedor indica restrição; nenhuma mensagem enviada' WHERE id=?",c.destination());s.stop(c.rid(),"Provedor indica restrição para iniciar conversas. Revisão técnica necessária.",true);s.db.update("UPDATE campanha_controles SET canal_restrito=true WHERE restaurante_id=?",c.rid());}
        });return !restricted;
    }
    static boolean restricted(Map<String,Object> limits){
        if(limits==null)return false;
        if(Boolean.FALSE.equals(limits.get("can_send_new_messages")))return true;
        if(limits.get("reachout_timelock") instanceof Map<?,?>lock&&Boolean.TRUE.equals(lock.get("active")))return true;
        if(limits.get("new_chat_message_capping") instanceof Map<?,?>cap){
            if("CAPPED".equals(cap.get("status"))||"EXHAUSTED".equals(cap.get("ote_status")))return true;
            if(cap.get("used_quota") instanceof Number used&&cap.get("total_quota") instanceof Number total&&total.longValue()>0&&used.longValue()>=total.longValue())return true;
        }return false;
    }
    static String providerId(Map<String,Object>m){
        for(String k:List.of("messageid","messageId","id")){Object v=m.get(k);if(v instanceof String text&&!text.isBlank()&&text.length()<=180)return text;}
        if(m.get("key") instanceof Map<?,?>key&&key.get("id") instanceof String id&&!id.isBlank()&&id.length()<=180)return id;
        return null;
    }
    void failure(Claim c,Exception e){
        RestClientResponseException http=null;for(Throwable x=e;x!=null;x=x.getCause())if(x instanceof RestClientResponseException h){http=h;break;}
        int status=http==null?0:http.getStatusCode().value();
        String body=http==null?"":CampaignPolicy.normalized(http.getResponseBodyAsString());
        final boolean restricted=status==401||status==403||body.matches("(?s).*(banned|suspended|account restricted|account blocked|spam detected|whatsapp_reachout_timelock|temporary restriction|provider_code.{0,4}463).*");
        final boolean permanent=status==400||status==404||status==413||status==415||status==422;
        final boolean invalid=permanent&&body.contains("not on whatsapp");
        final int code=status;
        long retryAfter=s.policy.getBackoffSegundos();
        if(http!=null&&http.getResponseHeaders()!=null){try{retryAfter=Math.max(retryAfter,Long.parseLong(http.getResponseHeaders().getFirst("Retry-After")));}catch(Exception ignored){}}
        final long providerWait=retryAfter;
        s.tx.executeWithoutResult(t->{
            s.config(c.rid(),true);var d=s.db.queryForMap("SELECT * FROM campanha_destinos WHERE id=? FOR UPDATE",c.destination());
            if(!"PROCESSANDO".equals(str(d,"status")))return;
            int attempts=(int)num(d,"tentativas");
            // Apenas 429 é rejeição explícita transitória conhecida; timeout/5xx não prova ausência de envio.
            if(code==429&&!restricted&&attempts<s.policy.getMaxTentativas()){
                LocalDateTime next=now().plusSeconds(Math.max(providerWait,(long)s.policy.getBackoffSegundos()*(1L<<Math.min(attempts-1,8))));
                s.db.update("UPDATE campanha_destinos SET status='PENDENTE',proxima_tentativa=?,motivo='Provedor limitou a taxa; aguardando nova tentativa controlada' WHERE id=?",next,c.destination());
                s.db.update("UPDATE campanha_controles SET proximo_envio=?,motivo='Taxa limitada pelo provedor' WHERE restaurante_id=?",next,c.rid());
                s.audit(c.rid(),"BACKOFF","Rejeição HTTP 429; tentativa "+attempts);return;
            }
            String reason=restricted?"Provedor recusou autorização ou sinalizou restrição. Revisão técnica necessária.":invalid?"Número não está disponível no WhatsApp.":permanent?"Provedor rejeitou a mensagem (HTTP "+code+"). Revisar campanha.":code==429?"Limite do provedor persistiu após tentativas controladas.":"Resultado de envio incerto. Não repetir automaticamente; revisar com o suporte.";
            s.db.update("UPDATE campanha_destinos SET status=?,motivo=? WHERE id=?",restricted||permanent||code==429?"FALHA":"INCERTO",reason,c.destination());
            if(invalid)s.db.update("UPDATE campanha_contatos SET restrito=true WHERE restaurante_id=? AND telefone=?",c.rid(),c.phone());
            s.stop(c.rid(),reason,restricted||(!permanent&&code!=429));
            if(restricted)s.db.update("UPDATE campanha_controles SET canal_restrito=true WHERE restaurante_id=?",c.rid());
        });
    }
}
