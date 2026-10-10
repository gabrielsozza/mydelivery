package com.mydelivery.campanhas;

import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.net.URI;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class CampaignService {
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final CampaignPolicy policy;
    final ObjectMapper json;
    public CampaignService(JdbcTemplate db,PlatformTransactionManager tm,CampaignPolicy policy,ObjectMapper json) {
        this.db=db; this.tx=new TransactionTemplate(tm); this.policy=policy; this.json=json;
    }
    static LocalDateTime now(){return LocalDateTime.now(CampaignPolicy.ZONE);}
    static long num(Map<String,Object> row,String key){Object v=row.get(key);return v==null?0:((Number)v).longValue();}
    static String str(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
    static boolean yes(Object value){return Boolean.TRUE.equals(value)||(value instanceof Number n&&n.intValue()!=0);}
    static LocalDateTime time(Object v){return v instanceof Timestamp t?t.toLocalDateTime():v instanceof LocalDateTime d?d:null;}
    static ResponseStatusException bad(String s){return new ResponseStatusException(BAD_REQUEST,s);}
    void ownStore(long rid){if(com.mydelivery.crm.ProspectingPolicy.isStore(rid))throw bad("A loja comercial utiliza a fila própria do CRM.");}
    Map<String,Object> config(long rid,boolean lock){
        db.update("INSERT INTO campanha_controles(restaurante_id) VALUES(?) ON DUPLICATE KEY UPDATE restaurante_id=VALUES(restaurante_id)",rid);
        return db.queryForMap("SELECT * FROM campanha_controles WHERE restaurante_id=?"+(lock?" FOR UPDATE":""),rid);
    }
    Map<String,Object> owned(long rid,long id,boolean lock){
        var rows=db.queryForList("SELECT * FROM campanhas_crm WHERE restaurante_id=? AND id=?"+(lock?" FOR UPDATE":""),rid,id);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Campanha não encontrada nesta loja.");
        return rows.getFirst();
    }
    void audit(long rid,String type,String detail){
        db.update("INSERT INTO campanha_eventos(restaurante_id,chave,tipo,detalhe,criado_em) VALUES(?,?,?,?,?)",rid,UUID.randomUUID().toString(),type,detail,now());
    }
    public Map<String,Object> settings(long rid){
        var c=config(rid,false); c.put("canal","Uazapi (integração não oficial)");
        c.put("entregaDisponivel",yes(c.get("recibos_disponiveis")));c.put("qualidadeDisponivel",false);
        c.put("aviso","A fila prioriza clientes com mais pedidos, depois um pedido e, por último, sem pedidos registrados.");
        c.put("tetos",Map.of("hora",policy.getMaxHora(),"dia",policy.getMaxDia(),"intervalo",policy.getMinIntervaloSegundos(),"diasCliente",policy.getMinDiasCliente(),"em30Dias",policy.getMax30Dias()));
        return c;
    }
    static int bounded(Map<String,Object>b,String key,int low,int high){
        try{int n=Integer.parseInt(Objects.toString(b.get(key),""));if(n<low||n>high)throw new Exception();return n;}
        catch(Exception e){throw bad("Valor inválido para "+key+" ("+low+" a "+high+").");}
    }
    public Map<String,Object> saveSettings(long rid,Map<String,Object>b){
        throw new ResponseStatusException(FORBIDDEN,"Os limites das campanhas são definidos pelo MyDelivery.");
    }

    record Contact(String phone,String name,int orders,LocalDateTime last,boolean cash,Set<Long>products,Set<Long>categories){}
    List<Contact> base(long rid){
        Map<String,Contact> contacts=new LinkedHashMap<>();
        // Dados de pedidos são da própria loja e vinculados a um cliente da mesma loja.
        var rows=db.queryForList("SELECT c.id,c.nome,c.telefone,COUNT(p.id) compras,MAX(p.criado_em) ultimo FROM clientes c LEFT JOIN pedidos p ON p.cliente_id=c.id AND p.restaurante_id=c.restaurante_id AND p.status<>'CANCELADO' WHERE c.restaurante_id=? GROUP BY c.id,c.nome,c.telefone ORDER BY c.id",rid);
        Map<String,Boolean> cash=new HashMap<>();
        for(var x:db.queryForList("SELECT m.telefone,SUM(m.valor) saldo FROM mycash_movimentos m JOIN mycash_config cfg ON cfg.restaurante_id=m.restaurante_id AND cfg.ativo=true WHERE m.restaurante_id=? GROUP BY m.telefone",rid))
            if(((Number)x.get("saldo")).doubleValue()>0)cash.put(CampaignPolicy.phone(str(x,"telefone")),true);
        Map<String,Set<Long>> products=new HashMap<>(),categories=new HashMap<>();
        for(var x:db.queryForList("SELECT DISTINCT c.telefone,i.produto_id,pr.categoria_id FROM pedidos p JOIN clientes c ON c.id=p.cliente_id AND c.restaurante_id=p.restaurante_id JOIN pedido_itens i ON i.pedido_id=p.id JOIN produtos pr ON pr.id=i.produto_id AND pr.restaurante_id=p.restaurante_id WHERE p.restaurante_id=? AND p.status<>'CANCELADO'",rid)){
            String tel=CampaignPolicy.phone(str(x,"telefone")); products.computeIfAbsent(tel,k->new HashSet<>()).add(num(x,"produto_id"));categories.computeIfAbsent(tel,k->new HashSet<>()).add(num(x,"categoria_id"));
        }
        for(var r:rows){
            String tel=CampaignPolicy.phone(str(r,"telefone"));String key=tel==null?"invalid:"+r.get("id"):tel;
            Contact old=contacts.get(key);int orders=(int)num(r,"compras")+(old==null?0:old.orders());
            LocalDateTime last=time(r.get("ultimo"));if(old!=null&&old.last()!=null&&(last==null||old.last().isAfter(last)))last=old.last();
            contacts.put(key,new Contact(tel,str(r,"nome"),orders,last,cash.getOrDefault(tel,false),products.getOrDefault(tel,Set.of()),categories.getOrDefault(tel,Set.of())));
        }
        return contacts.values().stream().sorted(Comparator.comparingInt(Contact::orders).reversed()).toList();
    }
    record Eligibility(Set<String> optouts,Map<String,Map<String,Object>> contacts,Map<String,List<LocalDateTime>> sent){}
    Eligibility eligibility(long rid) {
        Set<String> out=new HashSet<>();
        for(var r:db.queryForList("SELECT telefone FROM promo_opt_outs WHERE restaurante_id=?",rid))out.add(CampaignPolicy.phone(str(r,"telefone")));
        Map<String,Map<String,Object>> contacts=new HashMap<>();
        for(var r:db.queryForList("SELECT * FROM campanha_contatos WHERE restaurante_id=?",rid))contacts.put(str(r,"telefone"),r);
        Map<String,List<LocalDateTime>> sent=new HashMap<>();
        for(var r:db.queryForList("SELECT telefone,aceito_em instante FROM campanha_destinos WHERE restaurante_id=? AND aceito_em>=? UNION ALL SELECT e.telefone,e.enviado_em instante FROM promo_campanha_envios e JOIN promo_campanhas c ON c.id=e.campanha_id WHERE c.restaurante_id=? AND e.sucesso=true AND e.enviado_em>=?",rid,now().minusDays(365),rid,now().minusDays(365)))
            sent.computeIfAbsent(CampaignPolicy.phone(str(r,"telefone")),k->new ArrayList<>()).add(time(r.get("instante")));
        return new Eligibility(out,contacts,sent);
    }
    String exclusion(long rid,Contact c,Map<String,Object>cfg){return exclusion(c,cfg,eligibility(rid));}
    String exclusion(Contact c,Map<String,Object>cfg,Eligibility history){
        if(c.phone()==null)return "Contato inválido";
        if(history.optouts().contains(c.phone()))return "Não contatar";
        if(yes(cfg.get("bloqueado")))return "Canal precisa de revisão";
        var contact=history.contacts().get(c.phone());
        if(contact!=null&&yes(contact.get("restrito")))return "Contato restrito";
        // A loja decide a própria base de relacionamento. O registro de
        // autorização continua disponível, mas não bloqueia o envio.
        // Solicitações de não contato e restrições do canal sempre prevalecem.
        return null;
    }
    boolean optedOut(long rid,String phone){
        return db.queryForList("SELECT telefone FROM promo_opt_outs WHERE restaurante_id=?",rid).stream().anyMatch(r->phone.equals(CampaignPolicy.phone(str(r,"telefone"))));
    }
    boolean selected(Contact c,Map<String,Object>b){
        int days=b.get("inativo_dias")==null?30:bounded(b,"inativo_dias",1,730);
        boolean inactive=c.last()!=null&&c.last().isBefore(now().minusDays(days));
        return switch(str(b,"segmento")){
            case "RECORRENTES"->c.orders()>=2;
            case "INATIVOS"->inactive;
            case "MYCASH"->c.cash();
            case "MYCASH_INATIVOS"->c.cash()&&inactive;
            case "PRODUTO"->c.products().contains(Long.parseLong(str(b,"produto_id")));
            case "CATEGORIA"->c.categories().contains(Long.parseLong(str(b,"categoria_id")));
            case "COMPRADORES"->c.orders()>=1;
            case "BASE"->true;
            default->throw bad("Segmento inválido.");
        };
    }
    public Map<String,Object> preview(long rid,Map<String,Object>b){
        ownStore(rid);var cfg=config(rid,false);List<Contact> selected=base(rid).stream().filter(c->selected(c,b)).toList();
        Map<String,Integer> reasons=new LinkedHashMap<>();List<Contact> eligible=new ArrayList<>();
        var history=eligibility(rid);
        for(Contact c:selected){String reason=exclusion(c,cfg,history);if(reason==null)eligible.add(c);else reasons.merge(reason,1,Integer::sum);}
        var m=new LinkedHashMap<String,Object>();m.put("selecionados",selected.size());m.put("elegiveis",eligible.size());m.put("excluidos",selected.size()-eligible.size());m.put("motivos",reasons);
        m.put("previa",CampaignPolicy.message(eligible.isEmpty()?null:eligible.getFirst().name(),str(b,"texto")));
        m.put("imagemUrl",b.getOrDefault("imagemUrl",str(b,"imagem_url")));m.put("exemploReal",!eligible.isEmpty());return m;
    }
    static String image(String raw){
        if(raw==null||raw.isBlank())return null;
        try{URI u=URI.create(raw);if(raw.length()>1400||!"https".equals(u.getScheme())||!"res.cloudinary.com".equals(u.getHost())||u.getPort()!=-1||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!u.getPath().matches("/[^/]+/image/upload/.+"))throw new Exception();return raw.contains("/image/upload/f_jpg,")?raw:raw.replace("/image/upload/","/image/upload/f_jpg,q_auto,w_1600,c_limit/");}
        catch(Exception e){throw bad("Use uma imagem enviada pelo botão da campanha.");}
    }
    public Map<String,Object> create(long rid,Map<String,Object>b){
        ownStore(rid);String key=str(b,"chave"),text=str(b,"texto").trim(),img=image(str(b,"imagemUrl"));
        try{UUID.fromString(key);}catch(Exception e){throw bad("Chave da solicitação inválida. Atualize a página.");}
        if(text.isEmpty()||text.length()>700)throw bad("Escreva uma mensagem de até 700 caracteres.");
        preview(rid,b); // valida segmento antes de persistir
        Long id=tx.execute(t->{config(rid,true);var existing=db.queryForList("SELECT * FROM campanhas_crm WHERE restaurante_id=? AND chave=?",rid,key);
            if(!existing.isEmpty()){
                var previous=existing.getFirst();
                if(!text.equals(str(previous,"texto"))||!Objects.equals(img,previous.get("imagem_url"))||!str(b,"segmento").equals(str(previous,"segmento"))||!Objects.toString(b.getOrDefault("inativo_dias",30)).equals(Objects.toString(previous.get("inativo_dias")))||!Objects.toString(b.get("produto_id"),"").equals(str(previous,"produto_id"))||!Objects.toString(b.get("categoria_id"),"").equals(str(previous,"categoria_id")))
                    throw new ResponseStatusException(CONFLICT,"Esta solicitação já criou um rascunho diferente. Atualize a página para preparar outra campanha.");
                return num(previous,"id");
            }
            db.update("INSERT INTO campanhas_crm(restaurante_id,chave,texto,imagem_url,segmento,inativo_dias,produto_id,categoria_id,criada_em) VALUES(?,?,?,?,?,?,?,?,?)",rid,key,text,img,str(b,"segmento"),b.getOrDefault("inativo_dias",30),b.get("produto_id"),b.get("categoria_id"),now());
            return db.queryForObject("SELECT id FROM campanhas_crm WHERE restaurante_id=? AND chave=?",Long.class,rid,key);});
        return detail(rid,id);
    }
    public Map<String,Object> start(long rid,long id,String schedule){
        tx.executeWithoutResult(t->{var cfg=config(rid,true);var c=owned(rid,id,true);String state=str(c,"status");
            if(Set.of("ENVIANDO","AGENDADA").contains(state))return;
            if(!Set.of("RASCUNHO","PAUSADA").contains(state))throw bad("Campanha encerrada.");
            if(yes(cfg.get("bloqueado")))throw bad("Canal bloqueado para campanhas: "+str(cfg,"motivo"));
            var inst=db.queryForList("SELECT instance_name,phone,status FROM whatsapp_instances WHERE restaurante_id=?",rid);
            if(inst.isEmpty()||!"CONECTADA".equals(str(inst.getFirst(),"status")))throw bad("Conecte o WhatsApp antes de iniciar.");
            var i=inst.getFirst();if(c.get("instance_name")!=null&&(!Objects.equals(c.get("instance_name"),i.get("instance_name"))||!Objects.equals(c.get("instance_phone"),i.get("phone"))))throw bad("A sessão ou o número mudou. Esta campanha não pode continuar por outro canal.");
            if(db.queryForObject("SELECT COUNT(*) FROM campanha_destinos WHERE campanha_id=? AND (status='PROCESSANDO' OR (status='INCERTO' AND revisado_em IS NULL))",Long.class,id)>0)throw bad("Há envio com resultado incerto. Não será repetido. Revise com o suporte antes de continuar.");
            if(state.equals("RASCUNHO")){
                var p=preview(rid,c);if(((Number)p.get("elegiveis")).intValue()==0)throw bad("Nenhum cliente elegível. Confira os motivos e as autorizações.");
                var history=eligibility(rid);
                for(Contact contact:base(rid))if(selected(contact,c)){
                    String reason=exclusion(contact,cfg,history);if(reason==null)db.update("INSERT INTO campanha_destinos(campanha_id,restaurante_id,telefone,nome,pedidos) VALUES(?,?,?,?,?)",id,rid,contact.phone(),contact.name(),contact.orders());
                }
                String reasons;try{reasons=json.writeValueAsString(p.get("motivos"));}catch(Exception e){throw new IllegalStateException(e);}
                db.update("UPDATE campanhas_crm SET selecionados=?,elegiveis=?,exclusoes=?,atribuicao_dias=?,instance_name=?,instance_phone=? WHERE id=?",p.get("selecionados"),p.get("elegiveis"),reasons,num(cfg,"atribuicao_dias"),i.get("instance_name"),i.get("phone"),id);
            }
            LocalDateTime when=now();if(schedule!=null&&!schedule.isBlank()){try{when=LocalDateTime.parse(schedule);}catch(Exception e){throw bad("Data de agendamento inválida.");}if(when.isBefore(now()))throw bad("Escolha uma data futura (horário de Brasília).");}
            db.update("UPDATE campanhas_crm SET status=?,agendada_em=?,iniciada_em=COALESCE(iniciada_em,?),motivo=NULL WHERE id=?",when.isAfter(now())?"AGENDADA":"ENVIANDO",when,now(),id);audit(rid,"INICIO","Campanha "+id+" revisada e iniciada/agendada.");
        });return detail(rid,id);
    }
    public Map<String,Object> action(long rid,long id,String action){
        tx.executeWithoutResult(t->{config(rid,true);var c=owned(rid,id,true);if(Set.of("CANCELADA","CONCLUIDA").contains(str(c,"status")))return;
            if(!Set.of("pausar","cancelar").contains(action))throw bad("Ação inválida.");
            db.update("UPDATE campanhas_crm SET status=?,motivo=? WHERE id=?",action.equals("pausar")?"PAUSADA":"CANCELADA","Solicitação do responsável. Um envio já em processamento pode terminar.",id);
            if(action.equals("cancelar"))db.update("UPDATE campanha_destinos SET status='CANCELADO',motivo='Campanha cancelada' WHERE campanha_id=? AND status='PENDENTE'",id);
            audit(rid,action.toUpperCase(Locale.ROOT),"Campanha "+id);
        });return detail(rid,id);
    }
    public List<Map<String,Object>> list(long rid){
        return db.queryForList("SELECT id FROM campanhas_crm WHERE restaurante_id=? ORDER BY id DESC LIMIT 50",rid).stream().map(r->detail(rid,num(r,"id"))).toList();
    }
    public Map<String,Object> detail(long rid,long id){
        var c=owned(rid,id,false);var stats=db.queryForMap("SELECT SUM(CASE WHEN status IN ('PENDENTE','PROCESSANDO') THEN 1 ELSE 0 END) pendentes,COUNT(aceito_em) enviados,COUNT(entregue_em) entregues,COUNT(resposta_em) respostasProvaveis,SUM(CASE WHEN status='FALHA' THEN 1 ELSE 0 END) falhas,SUM(CASE WHEN status='INCERTO' THEN 1 ELSE 0 END) incertos,SUM(CASE WHEN status='IGNORADO' THEN 1 ELSE 0 END) excluidosNaFila,COUNT(opt_out_em) optouts FROM campanha_destinos WHERE restaurante_id=? AND campanha_id=?",rid,id);
        c.putAll(stats);c.put("entregaDisponivel",yes(config(rid,false).get("recibos_disponiveis")));
        c.putAll(db.queryForMap("SELECT COUNT(*) pedidosAtribuidos,COALESCE(SUM(p.total),0) receitaAtribuida FROM campanha_atribuicoes a JOIN pedidos p ON p.id=a.pedido_id AND p.restaurante_id=a.restaurante_id WHERE a.restaurante_id=? AND a.campanha_id=? AND p.pago=true AND p.status<>'CANCELADO' AND NOT EXISTS (SELECT 1 FROM pagamentos pg WHERE pg.pedido_id=p.id AND pg.status IN ('CANCELADO','EXPIRADO','RECUSADO'))",rid,id));
        return c;
    }
    public List<Map<String,Object>> recipients(long rid,long id){owned(rid,id,false);return db.queryForList("SELECT id,nome,telefone,status,motivo,tentativas,aceito_em,resposta_em,opt_out_em FROM campanha_destinos WHERE restaurante_id=? AND campanha_id=? ORDER BY id LIMIT 1000",rid,id);}
    public List<Map<String,Object>> contacts(long rid){
        var cfg=config(rid,false);var out=new ArrayList<Map<String,Object>>();var history=eligibility(rid);
        for(Contact c:base(rid)){
            Map<String,Object> m=new LinkedHashMap<>();m.put("nome",c.name());m.put("telefone",c.phone());m.put("pedidos",c.orders());String reason=exclusion(c,cfg,history);m.put("situacao",reason==null?"Elegível":reason);
            m.put("naoContatar",c.phone()!=null&&history.optouts().contains(c.phone()));
            if(c.phone()!=null){var p=history.contacts().get(c.phone());if(p!=null){m.put("consentimento_em",p.get("consentimento_em"));m.put("evidencia",p.get("evidencia"));m.put("ultima_resposta",p.get("ultima_resposta"));}}
            out.add(m);
        }return out;
    }
    public void consent(long rid,Map<String,Object>b,String actor){
        String phone=CampaignPolicy.phone(str(b,"telefone")),evidence=str(b,"evidencia").trim();
        if(phone==null||evidence.length()<20||evidence.length()>500)throw bad("Registre a origem, data e comprovação da autorização (20 a 500 caracteres).");
        if(base(rid).stream().noneMatch(c->phone.equals(c.phone())))throw bad("Contato não pertence à base desta loja.");
        tx.executeWithoutResult(t->{config(rid,true);if(optedOut(rid,phone))throw bad("Cliente está em NÃO CONTATAR. O registro de consentimento não remove esta proteção.");
            db.update("INSERT INTO campanha_contatos(restaurante_id,telefone,consentimento_em,evidencia,registrado_por) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE consentimento_em=VALUES(consentimento_em),evidencia=VALUES(evidencia),registrado_por=VALUES(registrado_por)",rid,phone,now(),evidence,actor);
            audit(rid,"CONSENTIMENTO","Autorização individual registrada por "+actor+" para final "+phone.substring(phone.length()-4));
        });
    }
    void stop(long rid,String reason,boolean restricted){
        db.update("UPDATE campanha_controles SET motivo=CASE WHEN bloqueado=true AND ?=false THEN motivo ELSE ? END,bloqueado=CASE WHEN ?=true THEN true ELSE bloqueado END WHERE restaurante_id=?",restricted,reason,restricted,rid);
        db.update("UPDATE campanhas_crm SET status='PAUSADA',motivo=? WHERE restaurante_id=? AND status IN ('ENVIANDO','AGENDADA')",reason,rid);
        audit(rid,restricted?"CANAL_RESTRITO":"PAUSA_AUTOMATICA",reason);
    }
}
