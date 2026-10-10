package com.mydelivery.crm;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;
import static com.mydelivery.crm.CrmIdentity.*;
import static com.mydelivery.crm.CrmStore.*;

@Service
@RequiredArgsConstructor
public class CrmService {
    public enum Stage { NOVO_LEAD, QUALIFICADO, CONTATO_PREPARADO, CONTATADO, RESPONDEU, INTERESSADO, DEMONSTRACAO, NEGOCIACAO, TRIAL_INICIADO, CLIENTE, PERDIDO }
    public enum Handoff { AUTOMATICO, AGUARDANDO_ATENDIMENTO_HUMANO, EM_ATENDIMENTO_HUMANO }
    private static final Set<String> PROFILE_FIELDS = Set.of("nome","responsavel","telefone","whatsapp","instagram","site","cidade","estado","bairro","segmento","origem","plataformaAtual","utilizaIfood","possuiCardapioProprio","pedidosWhatsApp","melhorarExperienciaPedido","encontradoEm","proximaAcao","nivelInteresse","observacoes","motivoPerda","informacoesPublicas","oportunidades","evidencias");
    private static final Set<String> BOOLEAN_FIELDS = Set.of("utilizaIfood","possuiCardapioProprio","pedidosWhatsApp","melhorarExperienciaPedido");
    private final CrmStore db;
    private final CrmApproachService approach;
    private final com.mydelivery.repository.RestauranteRepository restaurants;

    private void requireAssigned(long id,long collaboratorId) {
        if(number(db.lead(id).get("assigned_collaborator_id"))!=collaboratorId)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Lead não atribuído.");
    }
    @Transactional
    public Map<String,Object> commercialDetail(long id,long collaboratorId) {
        db.lock();requireAssigned(id,collaboratorId);
        Map<String,Object> result=detail(id);
        result.remove("outbox"); // Internal message queue and delivery metadata are admin-only.
        return result;
    }
    @Transactional
    public List<Map<String,Object>> commercialHistory(long id,long collaboratorId,long before) {
        db.lock();requireAssigned(id,collaboratorId);
        return history(id,before);
    }
    @Transactional
    public Map<String,Object> commercialUpdate(long id,long collaboratorId,Map<String,Object> profile,long version,String actor) {
        db.lock();requireAssigned(id,collaboratorId);
        return update(id,profile,version,actor);
    }
    @Transactional
    public Map<String,Object> commercialStage(long id,long collaboratorId,String next,String reason,long version,String actor) {
        if("CLIENTE".equals(next)) throw new IllegalArgumentException("Cadastro de cliente deve ser confirmado no MyDelivery.");
        db.lock();requireAssigned(id,collaboratorId);return stage(id,next,reason,version,actor);
    }
    @Transactional
    public void commercialNote(long id,long collaboratorId,String message,String actor) {
        db.lock();requireAssigned(id,collaboratorId);note(id,message,actor);
    }
    @Transactional
    public Map<String,Object> commercialMessage(long id,long collaboratorId,String message,long version,String actor) {
        db.lock();requireAssigned(id,collaboratorId);
        return saveMessage(id,message,version,actor);
    }
    @Transactional
    public Map<String,Object> commercialCall(long id,long collaboratorId,String outcome,String summary,
            String objection,String nextAction,String nextAt,long version,String actor) {
        db.lock();requireAssigned(id,collaboratorId);
        var lead=db.lead(id);checkVersion(lead,version);
        var allowed=Set.of("CONVERSOU","NAO_ATENDEU","PEDIU_RETORNO","SEM_INTERESSE","NUMERO_INCORRETO","OUTRO");
        if(!allowed.contains(text(outcome))) throw new IllegalArgumentException("Selecione o resultado da ligação.");
        String notes=text(summary).trim(),pain=text(objection).trim(),action=text(nextAction).trim(),at=text(nextAt).trim();
        if(notes.length()>3000 || pain.length()>500 || action.length()>300) throw new IllegalArgumentException("O registro da ligação excede o limite de texto.");
        if(Set.of("CONVERSOU","SEM_INTERESSE","OUTRO").contains(outcome) && notes.isBlank())
            throw new IllegalArgumentException("Resuma o que aconteceu na ligação.");
        if("PEDIU_RETORNO".equals(outcome) && action.isBlank())
            throw new IllegalArgumentException("Registre o próximo passo combinado para o retorno.");
        if(!at.isBlank() && action.isBlank()) throw new IllegalArgumentException("Descreva o próximo passo antes de definir a data.");
        LocalDateTime when=null;
        if(!at.isBlank()) {
            try { when=LocalDateTime.parse(at); }
            catch(Exception e) { throw new IllegalArgumentException("Data de retorno inválida."); }
            if(!when.isAfter(db.now())) throw new IllegalArgumentException("Escolha uma data futura para o retorno.");
        }
        if(!action.isBlank()) {
            var profile=db.profile(lead);profile.put("proximaAcao",action);
            db.jdbc().update("UPDATE crm_leads SET profile=?,next_action_at=?,version=version+1,last_interaction_at=? WHERE id=?",
                    db.json(profile),when,db.now(),id);
        } else db.jdbc().update("UPDATE crm_leads SET version=version+1,last_interaction_at=? WHERE id=?",db.now(),id);
        var details=new LinkedHashMap<String,Object>();
        details.put("resultado",outcome);details.put("resumo",notes);details.put("objecao",pain);
        details.put("proximoPasso",action);details.put("retornoEm",at);
        db.event(id,"LIGACAO_REGISTRADA",details,actor);
        return detail(id);
    }

    @Transactional
    public Map<String,Object> assign(long id, Long collaboratorId, String actor) {
        db.lock(); var lead=db.lead(id);
        Long previous=lead.get("assigned_collaborator_id")==null?null:number(lead.get("assigned_collaborator_id"));
        if(Objects.equals(previous,collaboratorId)) {
            Map<String,Object> result=db.dto(lead);result.put("alreadyAssigned",collaboratorId!=null);return result;
        }
        db.jdbc().update("UPDATE crm_leads SET assigned_collaborator_id=?,version=version+1 WHERE id=?",collaboratorId,id);
        if(collaboratorId!=null)db.jdbc().update("UPDATE crm_appointments SET collaborator_id=? WHERE lead_id=? AND status='PENDING'",collaboratorId,id);
        else db.jdbc().update("UPDATE crm_appointments SET status='CANCELLED' WHERE lead_id=? AND status='PENDING'",id);
        Map<String,Object> detail=new LinkedHashMap<>(); detail.put("anterior",previous); detail.put("atual",collaboratorId);
        db.event(id,"COLABORADOR_ATRIBUIDO",detail,actor);
        Map<String,Object> result=db.dto(db.lead(id));result.put("alreadyAssigned",false);return result;
    }

    @Transactional
    public Map<String,Object> createForCollaborator(Map<String,Object> profile,long collaboratorId,String actor) {
        long id=number(create(profile,actor).get("id"));
        return assign(id,collaboratorId,actor);
    }

    @Transactional
    public Map<String,Object> assignRegion(String city,String state,String neighborhood,long collaboratorId,boolean includeAssigned,String actor) {
        return assignRegion(city==null||city.isBlank()?List.of():List.of(city),state,neighborhood,collaboratorId,includeAssigned,actor);
    }

    @Transactional
    public Map<String,Object> assignRegion(List<String> cities,String state,String neighborhood,long collaboratorId,boolean includeAssigned,String actor) {
        Set<String> targetCities=cities==null?Set.of():cities.stream().map(s->normalized(s)).filter(s->!s.isBlank()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String targetState=normalized(state),targetNeighborhood=normalized(neighborhood);
        if(targetCities.isEmpty()) throw new IllegalArgumentException("Selecione uma ou mais cidades para distribuir a região.");
        if(collaboratorId<=0) throw new IllegalArgumentException("Selecione um colaborador.");
        db.lock();
        var rows=db.jdbc().queryForList("SELECT id,profile,assigned_collaborator_id FROM crm_leads ORDER BY id");
        int matched=0,skipped=0,assigned=0;
        for(var row:rows) {
            var profile=db.profile(row);
            if(!targetCities.contains(normalized(text(profile.get("cidade"))))) continue;
            if(!targetState.isBlank()&&!targetState.equals(normalized(text(profile.get("estado"))))) continue;
            if(!targetNeighborhood.isBlank()&&!targetNeighborhood.equals(normalized(text(profile.get("bairro"))))) continue;
            matched++;
            if(row.get("assigned_collaborator_id")!=null&&!includeAssigned) { skipped++;continue; }
            if(row.get("assigned_collaborator_id")!=null&&number(row.get("assigned_collaborator_id"))==collaboratorId) { skipped++;continue; }
            assign(number(row.get("id")),collaboratorId,actor);
            assigned++;
        }
        return Map.of("encontrados",matched,"atribuidos",assigned,"ignorados",skipped);
    }

    @Transactional
    public Map<String,Object> contactAttempt(long id,String channel,long collaboratorId,String actor) {
        if(!Set.of("WHATSAPP","PHONE").contains(channel)) throw new IllegalArgumentException("Canal inválido.");
        db.lock();var lead=db.lead(id);
        if(number(lead.get("assigned_collaborator_id"))!=collaboratorId) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Lead não atribuído.");
        if(bool(lead.get("do_not_contact"))) throw new IllegalArgumentException("Este lead está marcado como não contatar.");
        db.event(id,channel.equals("PHONE")?"TENTATIVA_LIGACAO":"WHATSAPP_ABERTO",Map.of("canal",channel,"resultado","NAO_CONFIRMADO"),actor);
        db.jdbc().update("UPDATE crm_leads SET last_interaction_at=?,version=version+1 WHERE id=?",db.now(),id);
        if(Set.of("NOVO_LEAD","QUALIFICADO","CONTATO_PREPARADO").contains(text(lead.get("stage")))) move(id,Stage.CONTATADO,"Tentativa de contato, sem confirmação de resposta",actor);
        return db.dto(db.lead(id));
    }

    @Transactional
    public Map<String,Object> schedule(long id,long collaboratorId,String channel,String at,String note,String actor) {
        if(!Set.of("WHATSAPP","PHONE").contains(channel)) throw new IllegalArgumentException("Canal inválido.");
        if(note!=null&&note.length()>1000) throw new IllegalArgumentException("Observação muito longa.");
        LocalDateTime when=LocalDateTime.parse(at);
        if(!when.isAfter(db.now())) throw new IllegalArgumentException("Escolha um horário futuro.");
        db.lock();var lead=db.lead(id);
        if(number(lead.get("assigned_collaborator_id"))!=collaboratorId) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Lead não atribuído.");
        long appointmentId=db.insert("INSERT INTO crm_appointments(lead_id,collaborator_id,contact_type,scheduled_at,note,status,created_at) VALUES (?,?,?,?,?,'PENDING',?)",id,collaboratorId,channel,when,text(note),db.now());
        var next=db.jdbc().queryForList("SELECT scheduled_at FROM crm_appointments WHERE lead_id=? AND status='PENDING' ORDER BY scheduled_at LIMIT 1",id);
        db.jdbc().update("UPDATE crm_leads SET next_action_at=?,version=version+1 WHERE id=?",next.getFirst().get("scheduled_at"),id);
        db.event(id,"RETORNO_AGENDADO",Map.of("agendamentoId",appointmentId,"canal",channel,"em",when.toString(),"observacao",text(note)),actor);
        return db.dto(db.jdbc().queryForMap("SELECT * FROM crm_appointments WHERE id=?",appointmentId));
    }

    @Transactional
    public Map<String,Object> completeAppointment(long appointmentId,long collaboratorId,String actor) {
        db.lock();var rows=db.jdbc().queryForList("SELECT * FROM crm_appointments WHERE id=? AND collaborator_id=?",appointmentId,collaboratorId);
        if(rows.isEmpty()) throw new NoSuchElementException("Agendamento não encontrado.");
        var appointment=rows.getFirst();
        requireAssigned(number(appointment.get("lead_id")),collaboratorId);
        if(!"PENDING".equals(text(appointment.get("status")))) return db.dto(appointment);
        db.jdbc().update("UPDATE crm_appointments SET status='COMPLETED',completed_at=? WHERE id=?",db.now(),appointmentId);
        long leadId=number(appointment.get("lead_id"));
        db.event(leadId,"RETORNO_CONCLUIDO",Map.of("agendamentoId",appointmentId),actor);
        var next=db.jdbc().queryForList("SELECT scheduled_at FROM crm_appointments WHERE lead_id=? AND status='PENDING' ORDER BY scheduled_at LIMIT 1",leadId);
        db.jdbc().update("UPDATE crm_leads SET next_action_at=?,version=version+1 WHERE id=?",next.isEmpty()?null:next.getFirst().get("scheduled_at"),leadId);
        return db.dto(db.jdbc().queryForMap("SELECT * FROM crm_appointments WHERE id=?",appointmentId));
    }

    public Map<String,Object> list(String search, String stage, int page) {
        return list(search,stage,page,null,null,List.of(),false);
    }

    public Map<String,Object> list(String search, String stage, int page,Long collaboratorId,String state,List<String> cities,boolean unassigned) {
        if(page<0) throw new IllegalArgumentException("Página inválida.");
        String query = "SELECT * FROM crm_leads WHERE 1=1";
        var args = new ArrayList<Object>();
        if(stage!=null&&!stage.isBlank()) { Stage.valueOf(stage); query+=" AND stage=?";args.add(stage); }
        if(search!=null&&!search.isBlank()) { query+=" AND LOWER(profile) LIKE ?";args.add("%"+search.toLowerCase(Locale.ROOT)+"%"); }
        if(collaboratorId!=null) { query+=" AND assigned_collaborator_id=?";args.add(collaboratorId); }
        if(unassigned) query+=" AND assigned_collaborator_id IS NULL";
        if(state!=null&&!state.isBlank()) { query+=" AND JSON_UNQUOTE(JSON_EXTRACT(profile,'$.estado'))=?";args.add(state.trim()); }
        List<String> selectedCities=cities==null?List.of():cities.stream().map(String::trim).filter(s->!s.isBlank()).distinct().toList();
        if(!selectedCities.isEmpty()) { query+=" AND JSON_UNQUOTE(JSON_EXTRACT(profile,'$.cidade')) IN ("+String.join(",",Collections.nCopies(selectedCities.size(),"?"))+")";args.addAll(selectedCities); }
        Long total = db.jdbc().queryForObject(query.replace("SELECT *", "SELECT COUNT(*)"),Long.class,args.toArray());
        query += " ORDER BY id DESC LIMIT 200 OFFSET ?"; args.add(Math.multiplyExact(page,200));
        return Map.of("items",db.jdbc().queryForList(query,args.toArray()).stream().map(db::dto).toList(),"total",total,"page",page,"size",200);
    }

    /**
     * Board view: page independently inside each CRM stage so a large batch of
     * recent new leads cannot hide older qualified cards in the other columns.
     */
    public Map<String,Object> pipeline(String search,String stage,int page,Long collaboratorId,String state,List<String> cities,boolean unassigned) {
        if(page<0) throw new IllegalArgumentException("Página inválida.");
        if(stage!=null&&!stage.isBlank()) Stage.valueOf(stage.trim());
        int pageSize=50;
        StringBuilder where=new StringBuilder(" WHERE 1=1");
        List<Object> args=new ArrayList<>();
        if(stage!=null&&!stage.isBlank()){where.append(" AND stage=?");args.add(stage.trim());}
        if(search!=null&&!search.isBlank()){where.append(" AND LOWER(profile) LIKE ?");args.add("%"+search.trim().toLowerCase(Locale.ROOT)+"%");}
        if(collaboratorId!=null){where.append(" AND assigned_collaborator_id=?");args.add(collaboratorId);}
        if(unassigned)where.append(" AND assigned_collaborator_id IS NULL");
        if(state!=null&&!state.isBlank()){where.append(" AND JSON_UNQUOTE(JSON_EXTRACT(profile,'$.estado'))=?");args.add(state.trim());}
        List<String> selectedCities=cities==null?List.of():cities.stream().map(String::trim).filter(s->!s.isBlank()).distinct().toList();
        if(!selectedCities.isEmpty()){where.append(" AND JSON_UNQUOTE(JSON_EXTRACT(profile,'$.cidade')) IN (").append(String.join(",",Collections.nCopies(selectedCities.size(),"?"))).append(")");args.addAll(selectedCities);}

        Map<String,Long> stageCounts=new LinkedHashMap<>();
        for(Stage value:Stage.values())stageCounts.put(value.name(),0L);
        db.jdbc().queryForList("SELECT stage,COUNT(*) total FROM crm_leads"+where+" GROUP BY stage",args.toArray())
                .forEach(row->stageCounts.put(String.valueOf(row.get("stage")),((Number)row.get("total")).longValue()));

        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(Math.multiplyExact(page,pageSize));pageArgs.add(Math.multiplyExact(page+1,pageSize));
        String sql="SELECT * FROM (SELECT crm_leads.*,ROW_NUMBER() OVER (PARTITION BY stage ORDER BY id DESC) AS pipeline_row FROM crm_leads"+where+
                ") board WHERE pipeline_row>? AND pipeline_row<=? ORDER BY stage,id DESC";
        List<Map<String,Object>> items=db.jdbc().queryForList(sql,pageArgs.toArray()).stream().map(row->{
            var clean=new LinkedHashMap<>(row);clean.remove("pipeline_row");return db.dto(clean);
        }).toList();
        long total=stageCounts.values().stream().mapToLong(Long::longValue).sum();
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",items);result.put("counts",stageCounts);result.put("total",total);result.put("page",page);result.put("size",pageSize);return result;
    }
    public Map<String,Object> detail(long id) {
        var result = db.dto(db.lead(id));
        result.put("timeline",db.jdbc().queryForList("SELECT * FROM crm_interactions WHERE lead_id=? ORDER BY id DESC LIMIT 500",id).stream().map(db::dto).toList());
        result.put("outbox",db.jdbc().queryForList("SELECT * FROM crm_outbox WHERE lead_id=? ORDER BY id DESC LIMIT 100",id).stream().map(db::dto).toList());
        return result;
    }
    public List<Map<String,Object>> history(long id, long before) {
        db.lead(id);
        return db.jdbc().queryForList("SELECT * FROM crm_interactions WHERE lead_id=? AND id<? ORDER BY id DESC LIMIT 500",id,before).stream().map(db::dto).toList();
    }

    /** Administrator-only removal. Delete dependent CRM rows explicitly because the schema keeps history by default. */
    @Transactional
    public void deleteLead(long id) {
        db.lock();
        db.lead(id);
        db.jdbc().update("DELETE FROM crm_outbox WHERE lead_id=?",id);
        db.jdbc().update("DELETE FROM crm_appointments WHERE lead_id=?",id);
        db.jdbc().update("DELETE FROM crm_interactions WHERE lead_id=?",id);
        db.jdbc().update("DELETE FROM crm_identities WHERE lead_id=?",id);
        db.jdbc().update("DELETE FROM crm_leads WHERE id=?",id);
    }
    private Map<String,Object> validated(Map<String,Object> input) {
        var p = new LinkedHashMap<String,Object>();
        for(var entry:input.entrySet()) {
            if(!PROFILE_FIELDS.contains(entry.getKey())) throw new IllegalArgumentException("Campo de qualificação desconhecido: "+entry.getKey());
            Object value=entry.getValue();
            if(BOOLEAN_FIELDS.contains(entry.getKey())) {
                if(value!=null && !(value instanceof Boolean)) throw new IllegalArgumentException("Valor sim/não inválido.");
                p.put(entry.getKey(),value);continue;
            }
            if(value!=null && !(value instanceof String)) throw new IllegalArgumentException("Campo textual inválido.");
            String s=text(value);
            if(s.length()>4000) throw new IllegalArgumentException("Campo excede 4000 caracteres.");
            p.put(entry.getKey(),s);
        }
        if(List.of("nome","telefone","whatsapp","instagram","site").stream().allMatch(k -> text(p.get(k)).isBlank())) throw new IllegalArgumentException("Informe ao menos nome, telefone, WhatsApp, Instagram ou site para identificar o lead.");
        for(String field:List.of("telefone","whatsapp")) p.put(field,phone(p.get(field)));
        if(!text(p.get("encontradoEm")).isBlank()) LocalDate.parse(text(p.get("encontradoEm")));
        keys(p); return p;
    }
    private void identities(long id, Map<String,Object> p) {
        for(String key:keys(p)) {
            var owner=db.jdbc().queryForList("SELECT lead_id FROM crm_identities WHERE identity_key=?",key);
            if(!owner.isEmpty() && number(owner.getFirst().get("lead_id"))!=id)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Estabelecimento já cadastrado: lead #"+owner.getFirst().get("lead_id")+". Abra o registro existente.");
            if(owner.isEmpty()) db.jdbc().update("INSERT INTO crm_identities(identity_key,lead_id) VALUES (?,?)",key,id);
        }
    }
    public boolean blockedPhone(String phone) {
        return db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_do_not_contact WHERE phone_key=?",Long.class,phoneKey(phone))>0;
    }
    @Transactional
    public Map<String,Object> create(Map<String,Object> input,String actor) {
        db.lock(); var p=validated(input); var now=db.now();
        long id=db.insert("INSERT INTO crm_leads(profile,created_at) VALUES (?,?)",db.json(p),now);
        identities(id,p);
        boolean blocked=List.of("telefone","whatsapp").stream().map(p::get).map(CrmIdentity::text).filter(s->!s.isBlank()).anyMatch(this::blockedPhone);
        if(blocked) db.jdbc().update("UPDATE crm_leads SET do_not_contact=TRUE WHERE id=?",id);
        db.event(id,"LEAD_CRIADO",p,actor);
        return db.dto(db.lead(id));
    }
    @Transactional
    public Map<String,Object> update(long id,Map<String,Object> input,long version,String actor) {
        db.lock();var old=db.lead(id); checkVersion(old,version);
        var p=validated(input); identities(id,p);
        // Identity aliases are retained permanently: editing must never allow recapture as a new lead.
        boolean blocked=bool(old.get("do_not_contact")) || List.of("telefone","whatsapp").stream().map(p::get).map(CrmIdentity::text).filter(s->!s.isBlank()).anyMatch(this::blockedPhone);
        db.jdbc().update("UPDATE crm_leads SET profile=?,do_not_contact=?,version=version+1 WHERE id=?",db.json(p),blocked,id);
        if(blocked) cancelQueued(id,"Não contatar");
        db.event(id,"QUALIFICACAO_EDITADA",Map.of("antes",db.profile(old),"depois",p),actor);
        return db.dto(db.lead(id));
    }
    private void checkVersion(Map<String,Object> lead,long version) {
        if(number(lead.get("version"))!=version) throw new ResponseStatusException(HttpStatus.CONFLICT,"Lead alterado por outra operação. Atualize antes de salvar.");
    }
    @Transactional
    public Map<String,Object> stage(long id,String next,String reason,long version,String actor) {
        db.lock();var lead=db.lead(id);checkVersion(lead,version);Stage stage=Stage.valueOf(next);
        if(stage==Stage.CLIENTE) throw new IllegalArgumentException("Vincule o restaurante cadastrado para confirmar CLIENTE.");
        if(stage==Stage.PERDIDO && text(reason).isBlank()) throw new IllegalArgumentException("Informe o motivo da perda.");
        if(stage==Stage.PERDIDO) { var p=db.profile(lead);p.put("motivoPerda",text(reason));db.jdbc().update("UPDATE crm_leads SET profile=? WHERE id=?",db.json(p),id);cancelQueued(id,"Lead perdido"); }
        move(id,stage,text(reason),actor);return db.dto(db.lead(id));
    }
    void move(long id,Stage next,String reason,String actor) {
        String prev=text(db.lead(id).get("stage")); if(prev.equals(next.name())) return;
        db.jdbc().update("UPDATE crm_leads SET stage=?,version=version+1 WHERE id=?",next.name(),id);
        db.event(id,"ETAPA_"+next.name(),Map.of("anterior",prev,"atual",next.name(),"motivo",reason),actor);
    }
    @Transactional
    public Map<String,Object> prepare(long id,String strategy,String actor) {
        db.lock();var lead=db.lead(id); var generated=approach.prepare(db.profile(lead),strategy);
        db.jdbc().update("UPDATE crm_leads SET prepared_message=?,version=version+1 WHERE id=?",generated.get("mensagem"),id);
        db.event(id,"ABORDAGEM_PREPARADA",generated,actor);
        if(Set.of("NOVO_LEAD","QUALIFICADO","CONTATO_PREPARADO").contains(text(lead.get("stage")))) move(id,Stage.CONTATO_PREPARADO,"Abordagem baseada na qualificação",actor);
        return detail(id);
    }
    @Transactional
    public Map<String,Object> saveMessage(long id,String message,long version,String actor) {
        db.lock();checkVersion(db.lead(id),version);validateMessage(message);
        db.jdbc().update("UPDATE crm_leads SET prepared_message=?,version=version+1 WHERE id=?",message.trim(),id);
        db.event(id,"ABORDAGEM_EDITADA",Map.of("mensagem",message.trim()),actor);return detail(id);
    }
    public static void validateMessage(String message) {
        if(message==null||message.isBlank()||message.length()>3000) throw new IllegalArgumentException("Mensagem deve ter entre 1 e 3000 caracteres.");
    }
    @Transactional
    public void note(long id,String message,String actor) {
        db.lock();db.lead(id);validateMessage(message);db.event(id,"OBSERVACAO",Map.of("texto",message),actor);
    }
    @Transactional
    public void nextAction(long id,String description,String at,String actor) {
        if (text(description).length()>3000) throw new IllegalArgumentException("Próxima ação excede 3000 caracteres.");
        db.lock(); var p=db.profile(db.lead(id));p.put("proximaAcao",text(description));
        LocalDateTime date=text(at).isBlank()?null:LocalDateTime.parse(at);
        db.jdbc().update("UPDATE crm_leads SET profile=?,next_action_at=?,version=version+1 WHERE id=?",db.json(p),date,id);
        db.event(id,"PROXIMA_ACAO",Map.of("acao",text(description),"em",text(at)),actor);
    }
    @Transactional
    public void handoff(long id,String state,String actor) {
        db.lock();db.lead(id);Handoff h=Handoff.valueOf(state);
        db.jdbc().update("UPDATE crm_leads SET handoff=?,version=version+1 WHERE id=?",h.name(),id);
        if(h!=Handoff.AUTOMATICO) cancelQueued(id,"Atendimento humano solicitado");
        db.event(id,"ATENDIMENTO_HUMANO",Map.of("estado",h.name()),actor);
    }
    @Transactional
    public void optOut(long id,String reason,String actor) {
        db.lock();var p=db.profile(db.lead(id));
        db.jdbc().update("UPDATE crm_leads SET do_not_contact=TRUE,version=version+1 WHERE id=?",id);
        var phoneKeys=db.jdbc().queryForList("SELECT identity_key FROM crm_identities WHERE lead_id=? AND identity_key LIKE 'phone:%'",id);
        for(var row:phoneKeys) blockNumber(text(row.get("identity_key")).substring(6),reason);
        cancelQueued(id,"Não contatar");db.event(id,"NAO_CONTATAR",Map.of("motivo",text(reason)),actor);
    }
    void blockNumber(String number,String reason) {
        String key=phoneKey(number);
        if(!key.isBlank()&&!blockedPhone(number)) db.jdbc().update("INSERT INTO crm_do_not_contact(phone_key,reason,created_at) VALUES (?,?,?)",key,text(reason),db.now());
    }
    void cancelQueued(long id,String reason) {
        for(var row:db.jdbc().queryForList("SELECT id FROM crm_outbox WHERE lead_id=? AND status='QUEUED' AND attempted_at IS NULL",id)) releaseUnsentKeys(number(row.get("id")));
        db.jdbc().update("UPDATE crm_outbox SET status='CANCELLED',error_detail=?,finished_at=? WHERE lead_id=? AND status='QUEUED'",reason,db.now(),id);
    }
    void releaseUnsentKeys(long queueId) {
        // No provider attempt occurred. Keep its original request id as a cancelled receipt;
        // a new explicit request may create another outbox row without duplicating any sent contact.
        db.jdbc().update("UPDATE crm_outbox SET initial_key=NULL,content_key=? WHERE id=? AND status='QUEUED' AND attempted_at IS NULL",hash("cancelled-before-send|"+queueId),queueId);
    }
    @Transactional
    public void customer(long id,long restaurantId,String actor) {
        db.lock();var lead=db.lead(id);
        if(ProspectingPolicy.isStore(restaurantId)) throw new IllegalArgumentException("A loja de teste não é uma conversão.");
        if(lead.get("customer_store_id")!=null && number(lead.get("customer_store_id"))!=restaurantId)
            throw new IllegalArgumentException("Lead já vinculado a outro restaurante.");
        if(db.jdbc().queryForObject("SELECT COUNT(*) FROM crm_leads WHERE customer_store_id=? AND id<>?",Long.class,restaurantId,id)>0) throw new IllegalArgumentException("Restaurante já vinculado a outro lead.");
        var rest=restaurants.findById(restaurantId).orElseThrow(()->new NoSuchElementException("Restaurante cadastrado não encontrado."));
        db.jdbc().update("UPDATE crm_leads SET customer_store_id=?,version=version+1 WHERE id=?",rest.getId(),id);
        move(id,Stage.CLIENTE,"Vínculo confirmado pelo admin com restaurante #"+rest.getId(),actor);
        cancelQueued(id,"Cliente convertido");
    }
    public Map<String,Object> config() {
        var out=db.dto(db.settings()); out.put("storeId",ProspectingPolicy.STORE_ID);out.put("stages",Arrays.stream(Stage.values()).map(Enum::name).toList());out.put("approaches",approach.catalog());return out;
    }
    @Transactional
    public Map<String,Object> config(Map<String,Object> input) {
        db.lock();int daily=bounded(input,"daily_limit",1,200), hourly=bounded(input,"hourly_limit",1,60), interval=bounded(input,"interval_seconds",30,3600);
        LocalTime start=LocalTime.parse(text(input.get("start_time"))),end=LocalTime.parse(text(input.get("end_time")));
        if(!start.isBefore(end)) throw new IllegalArgumentException("O início deve ser anterior ao término.");
        db.jdbc().update("UPDATE crm_settings SET enabled=?,daily_limit=?,hourly_limit=?,interval_seconds=?,start_time=?,end_time=? WHERE id=1",Boolean.TRUE.equals(input.get("enabled")),daily,hourly,interval,start.toString(),end.toString());return config();
    }
    private int bounded(Map<String,Object> data,String key,int min,int max) {
        Object value=data.get(key);if(!(value instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<min||n.intValue()>max) throw new IllegalArgumentException(key+": informe valor entre "+min+" e "+max);return n.intValue();
    }
    public Map<String,Object> metrics(LocalDate start,LocalDate end) {
        if(start==null||end==null||end.isBefore(start)||start.plusYears(2).isBefore(end)) throw new IllegalArgumentException("Período inválido (máximo 2 anos).");
        var from=start.atStartOfDay();var to=end.plusDays(1).atStartOfDay();var out=new LinkedHashMap<String,Object>();
        Map<String,Object> counts=db.jdbc().queryForMap("SELECT COUNT(*) encontrados, SUM(handoff='AGUARDANDO_ATENDIMENTO_HUMANO') aguardandoHumano, SUM(do_not_contact=TRUE) naoContatar FROM crm_leads WHERE created_at>=? AND created_at<?",from,to);
        // Global flags must include leads created outside this interval.
        Map<String,Object> global=db.jdbc().queryForMap("SELECT SUM(handoff='AGUARDANDO_ATENDIMENTO_HUMANO') aguardandoHumano,SUM(do_not_contact=TRUE) naoContatar FROM crm_leads");
        out.put("encontrados",number(counts.get("encontrados")));
        Map<String,Object> stages=db.jdbc().queryForMap("SELECT COUNT(DISTINCT CASE WHEN type='ETAPA_QUALIFICADO' THEN lead_id END) qualificado,COUNT(DISTINCT CASE WHEN type='ETAPA_DEMONSTRACAO' THEN lead_id END) demonstracao,COUNT(DISTINCT CASE WHEN type='ETAPA_CLIENTE' THEN lead_id END) cliente,COUNT(DISTINCT CASE WHEN type='ETAPA_PERDIDO' THEN lead_id END) perdido FROM crm_interactions WHERE created_at>=? AND created_at<? AND type IN ('ETAPA_QUALIFICADO','ETAPA_DEMONSTRACAO','ETAPA_CLIENTE','ETAPA_PERDIDO')",from,to);
        for(String s:List.of("qualificado","demonstracao","cliente","perdido"))out.put(s,number(stages.get(s)));
        // Cohort = first contact in the selected interval. Compute each first-contact timestamp once.
        String cohort="SELECT lead_id,MIN(created_at) first_contact_at FROM crm_interactions WHERE type='PRIMEIRO_CONTATO' AND created_at>=? AND created_at<? GROUP BY lead_id";
        Map<String,Object> outcomes=db.jdbc().queryForMap("SELECT COUNT(DISTINCT c.lead_id) contatados,COUNT(DISTINCT CASE WHEN i.type='RESPOSTA_RECEBIDA' THEN i.lead_id END) respostas,COUNT(DISTINCT CASE WHEN i.type='ETAPA_INTERESSADO' THEN i.lead_id END) interessados,COUNT(DISTINCT CASE WHEN i.type='ETAPA_DEMONSTRACAO' THEN i.lead_id END) demonstracoesCoorte,COUNT(DISTINCT CASE WHEN i.type='ETAPA_CLIENTE' THEN i.lead_id END) clientesCoorte FROM ("+cohort+") c LEFT JOIN crm_interactions i ON i.lead_id=c.lead_id AND i.created_at>=c.first_contact_at",from,to);
        long contacts=number(outcomes.get("contatados"));
        for(String key:List.of("contatados","respostas","interessados","demonstracoesCoorte","clientesCoorte"))out.put(key,number(outcomes.get(key)));
        for(var e:Map.of("taxaResposta","respostas","taxaInteresse","interessados","taxaDemonstracao","demonstracoesCoorte","taxaConversao","clientesCoorte").entrySet()) out.put(e.getKey(),contacts==0?0.0:100.0*number(out.get(e.getValue()))/contacts);
        out.put("aguardandoHumano",number(global.get("aguardandoHumano")));
        out.put("naoContatar",number(global.get("naoContatar")));return out;
    }
}
