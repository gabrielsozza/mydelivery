package com.mydelivery.crm;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import static com.mydelivery.crm.CrmIdentity.*;
import static com.mydelivery.crm.CrmStore.*;

@Service @RequiredArgsConstructor
public class CrmRegistrationService {
    private final CrmStore db;
    private final CrmService crm;
    @Transactional
    public void match(CrmRegistrationListener.Registration registration) {
        if(registration.collaboratorId()!=null){attachCommercialRegistration(registration);return;}
        if(ProspectingPolicy.isStore(registration.restaurantId())||text(registration.phone()).isBlank()) return;
        String key=phoneKey(registration.phone());db.lock();
        var matches=db.jdbc().queryForList("SELECT lead_id FROM crm_identities WHERE identity_key=?","phone:"+key);
        if(matches.size()!=1) return;
        long id=number(matches.getFirst().get("lead_id"));var lead=db.lead(id);
        if(lead.get("customer_store_id")!=null) return;
        var details=Map.of("restauranteId",registration.restaurantId(),"evidencia","Cadastro confirmado no servidor; telefone corresponde ao lead","tipoConversao","CADASTRO, não pagamento");
        db.event(id,"CADASTRO_IDENTIFICADO",details,"cadastro");
        // Exact establishment name + unique phone + recorded outreach; otherwise require human confirmation.
        boolean contacted=lead.get("sent_at")!=null || db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM crm_interactions WHERE lead_id=? AND type IN ('WHATSAPP_ABERTO','TENTATIVA_LIGACAO')",Long.class,id)>0;
        if(contacted&&!normalized(registration.name()).isBlank()&&normalized(registration.name()).equals(normalized(text(db.profile(lead).get("nome"))))) {
            db.jdbc().update("UPDATE crm_leads SET customer_store_id=?,version=version+1 WHERE id=?",registration.restaurantId(),id);
            crm.move(id,CrmService.Stage.CLIENTE,"Cadastro concluído: nome e telefone coincidem; restaurante #"+registration.restaurantId(),"cadastro");
            crm.cancelQueued(id,"Cadastro confirmado");
        } else crm.handoff(id,"AGUARDANDO_ATENDIMENTO_HUMANO","cadastro");
    }

    private void attachCommercialRegistration(CrmRegistrationListener.Registration registration) {
        db.lock();
        String storeKey="store:"+registration.restaurantId();
        var existing=db.jdbc().queryForList("SELECT lead_id FROM crm_identities WHERE identity_key=?",storeKey);
        long id;
        if(!existing.isEmpty()) id=number(existing.getFirst().get("lead_id"));
        else {
            String phone="";
            try{phone=phoneKey(registration.phone());}catch(IllegalArgumentException ignored){}
            Map<String,Object> prior=null;
            if(registration.leadId()!=null) {
                prior=db.lead(registration.leadId());
                if(number(prior.get("assigned_collaborator_id"))!=registration.collaboratorId())
                    throw new IllegalArgumentException("O link de cadastro não pertence à carteira deste colaborador.");
                if(prior.get("customer_store_id")!=null)
                    throw new IllegalArgumentException("Este lead já está vinculado a outro restaurante.");
            } else {
                var matches=phone.isBlank()?java.util.List.<Map<String,Object>>of():db.jdbc().queryForList(
                        "SELECT lead_id FROM crm_identities WHERE identity_key=?","phone:"+phone);
                if(matches.size()==1) {
                    var candidate=db.lead(number(matches.getFirst().get("lead_id")));
                    if(candidate.get("customer_store_id")==null
                            &&number(candidate.get("assigned_collaborator_id"))==registration.collaboratorId()
                            &&normalized(registration.name()).equals(normalized(text(db.profile(candidate).get("nome"))))) prior=candidate;
                }
                if(prior==null&&!normalized(registration.name()).isBlank()) {
                    // The generic salesperson link has no lead ID. Reuse only one exact,
                    // unlinked establishment in that salesperson's portfolio.
                    var candidates=db.jdbc().queryForList("SELECT * FROM crm_leads WHERE assigned_collaborator_id=? AND customer_store_id IS NULL AND stage<>'PERDIDO'",registration.collaboratorId())
                            .stream().filter(row->normalized(registration.name()).equals(normalized(text(db.profile(row).get("nome"))))).toList();
                    if(candidates.size()>1) throw new IllegalArgumentException("Há mais de um lead com este nome. Envie o link individual do lead.");
                    if(candidates.size()==1) prior=candidates.getFirst();
                }
            }
            if(prior!=null) id=number(prior.get("id"));
            else {
                var profile=new java.util.LinkedHashMap<String,Object>();
                profile.put("nome",registration.name());profile.put("telefone",text(registration.phone()));profile.put("origem","Colaborador");
                // Um telefone já usado por outro estabelecimento não deve transferir a carteira dele.
                var matches=phone.isBlank()?java.util.List.<Map<String,Object>>of():db.jdbc().queryForList(
                        "SELECT lead_id FROM crm_identities WHERE identity_key=?","phone:"+phone);
                if(!matches.isEmpty()){
                    id=db.insert("INSERT INTO crm_leads(profile,created_at) VALUES (?,?)",db.json(profile),db.now());
                    if(crm.blockedPhone(registration.phone()))
                        db.jdbc().update("UPDATE crm_leads SET do_not_contact=TRUE WHERE id=?",id);
                    db.event(id,"LEAD_CRIADO",profile,"cadastro:colaborador");
                } else id=number(crm.create(profile,"cadastro:colaborador").get("id"));
            }
            db.jdbc().update("INSERT INTO crm_identities(identity_key,lead_id) VALUES (?,?)",storeKey,id);
        }
        var lead=db.lead(id);
        if(lead.get("customer_store_id")!=null&&number(lead.get("customer_store_id"))!=registration.restaurantId())
            throw new IllegalStateException("Lead vinculado a outro restaurante.");
        var profile=db.profile(lead);
        String phone="";
        try{phone=phoneKey(registration.phone());}catch(IllegalArgumentException ignored){}
        if(!phone.isBlank()) {
            var owner=db.jdbc().queryForList("SELECT lead_id FROM crm_identities WHERE identity_key=?","phone:"+phone);
            if(owner.isEmpty()) db.jdbc().update("INSERT INTO crm_identities(identity_key,lead_id) VALUES (?,?)","phone:"+phone,id);
            if(owner.isEmpty()||number(owner.getFirst().get("lead_id"))==id) profile.put("telefone",text(registration.phone()));
        }
        db.jdbc().update("UPDATE crm_leads SET customer_store_id=?,profile=?,version=version+1 WHERE id=?",
                registration.restaurantId(),db.json(profile),id);
        crm.assign(id,registration.collaboratorId(),"cadastro:colaborador");
        crm.move(id,CrmService.Stage.CLIENTE,"Cadastro pelo link do colaborador; restaurante #"+registration.restaurantId(),"cadastro:colaborador");
        crm.cancelQueued(id,"Cadastro confirmado pelo link do colaborador");
        db.event(id,"CADASTRO_IDENTIFICADO",Map.of("restauranteId",registration.restaurantId(),"colaboradorId",registration.collaboratorId(),"evidencia","Link comercial assinado"),"cadastro:colaborador");
    }
}
