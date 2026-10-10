package com.mydelivery.beneficios;

import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.*;
import com.mydelivery.service.AssinaturaService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import static com.mydelivery.beneficios.BeneficiosSupport.*;

@Service
@RequiredArgsConstructor
public class MyMoneyService {
    private final JdbcTemplate db;
    private final RestauranteRepository restaurants;
    private final AssinaturaRepository subscriptions;
    private final AssinaturaService subscriptionService;

    // One database mutex for attribution, payment validation, awards and popup claims.
    // This also serializes multiple application instances; no in-memory idempotency.
    private void lock() { db.queryForObject("SELECT id FROM mymoney_config WHERE id=1 FOR UPDATE",Integer.class); }
    public boolean eligible(long rid) {
        return db.queryForObject("SELECT COUNT(*) FROM assinaturas a WHERE a.restaurante_id=? AND a.status='ATIVA' AND (a.trial_fim IS NULL OR a.trial_fim<=CURRENT_TIMESTAMP) AND EXISTS (SELECT 1 FROM pagamentos_mensalidade p WHERE p.restaurante_id=a.restaurante_id AND p.status='PAGO' AND p.valor>0)",Integer.class,rid)>0;
    }
    private void audit(Long rid,String actor,String action,String detail) {
        db.update("INSERT INTO mymoney_auditoria(restaurante_id,ator,acao,detalhe) VALUES(?,?,?,?)",rid,actor,action,detail);
    }
    private String ensureParticipant(long rid) {
        List<String> codes=db.queryForList("SELECT codigo FROM mymoney_participantes WHERE restaurante_id=?",String.class,rid);
        if(!codes.isEmpty()) return codes.getFirst();
        String code="MM"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT);
        db.update("INSERT INTO mymoney_participantes(restaurante_id,codigo) VALUES(?,?)",rid,code);
        audit(rid,"sistema","PARTICIPACAO","Código exclusivo criado.");return code;
    }
    @Transactional
    public void attribute(Restaurante referred,String code) {
        if(text(code).isEmpty()) return;
        lock();
        if(!text(referred.getAfiliadoCodigo()).isEmpty()) throw bad("O cadastro só pode ter uma origem de indicação.");
        var rows=db.queryForList("SELECT restaurante_id FROM mymoney_participantes WHERE codigo=?",text(code));
        if(rows.isEmpty()) throw bad("Link de indicação inválido. Confira o link recebido.");
        long source=id(rows.getFirst().get("restaurante_id"));
        // Previously issued links remain attributable; rewards wait for the referrer to be eligible again.
        Restaurante referrer=restaurants.findById(source).orElseThrow();
        if(source==referred.getId()) throw bad("Uma loja não pode indicar a si mesma.");
        String reason=suspicion(referrer,referred);
        db.update("INSERT INTO mymoney_indicacoes(indicador_id,indicado_id,codigo,status,motivo_revisao) VALUES(?,?,?,?,?)",
            source,referred.getId(),code,reason.isEmpty()?"PENDENTE":"EM_REVISAO",reason.isEmpty()?null:reason);
        audit(source,"cadastro","ATRIBUICAO","Restaurante indicado #"+referred.getId()+"; origem myMoney; "+(reason.isEmpty()?"aguardando primeira assinatura paga":reason));
    }
    private String suspicion(Restaurante a,Restaurante b) {
        if(a.getUsuario()!=null && b.getUsuario()!=null && Objects.equals(a.getUsuario().getId(),b.getUsuario().getId())) return "Mesmo proprietário";
        for(String[] pair:List.of(new String[]{a.getCnpj(),b.getCnpj()},new String[]{a.getCpf(),b.getCpf()},new String[]{a.getTelefone(),b.getTelefone()})) {
            String x=text(pair[0]).replaceAll("\\D",""),y=text(pair[1]).replaceAll("\\D","");
            if(x.startsWith("55")&&x.length()>=12&&x.length()<=13)x=x.substring(2);
            if(y.startsWith("55")&&y.length()>=12&&y.length()<=13)y=y.substring(2);
            if(x.length()>=10&&x.equals(y))return "Telefone ou documento coincidente com o indicador; conferir titularidade";
        }
        return "";
    }
    @Transactional
    public Map<String,Object> dashboard(long rid,boolean popup) {
        lock();
        boolean eligible=eligible(rid);
        Map<String,Object> out=new LinkedHashMap<>();out.put("elegivel",eligible);out.put("valorIndicacao",db.queryForObject("SELECT valor_indicacao FROM mymoney_config WHERE id=1",BigDecimal.class));
        out.put("metas",db.queryForList("SELECT id,patamar,meses,pix FROM mymoney_metas WHERE ativo=TRUE ORDER BY patamar"));
        boolean existing=db.queryForObject("SELECT COUNT(*) FROM mymoney_participantes WHERE restaurante_id=?",Integer.class,rid)>0;
        out.put("participante",existing||eligible);
        if(!eligible && !existing) {out.put("mostrarPopup",false);return out;}
        String code=ensureParticipant(rid);
        out.put("codigo",code);out.put("link","https://mydeliveryfood.com.br/login.html?ref="+code+"#cadastro");
        out.put("progresso",progress(rid));
        out.put("indicacoes",db.queryForList("SELECT i.id,r.nome,i.status,i.valor,i.criado_em,i.validado_em FROM mymoney_indicacoes i JOIN restaurantes r ON r.id=i.indicado_id WHERE i.indicador_id=? ORDER BY i.id DESC",rid));
        out.put("premios",db.queryForList("SELECT id,meta_id,patamar,meses,pix,status,pix_previsto_em,pix_pago_em,revisao_necessaria,meses_aplicados_em FROM mymoney_premios WHERE restaurante_id=? ORDER BY patamar",rid));
        out.put("carteira",walletSummary(rid));
        out.put("saques",db.queryForList("SELECT id,valor,status,previsto_em,pago_em,criado_em,motivo_cancelamento FROM mymoney_saques WHERE restaurante_id=? ORDER BY id DESC",rid));
        out.put("recebedor",db.queryForMap("SELECT pix_nome,pix_documento,pix_chave FROM mymoney_participantes WHERE restaurante_id=?",rid));
        boolean show=false;
        if(popup && eligible) {
            LocalDate today=LocalDate.now(ZONE),monday=today.with(java.time.DayOfWeek.MONDAY);
            int todayCount=db.queryForObject("SELECT COUNT(*) FROM mymoney_popup WHERE restaurante_id=? AND dia=?",Integer.class,rid,today);
            int weekCount=db.queryForObject("SELECT COUNT(*) FROM mymoney_popup WHERE restaurante_id=? AND dia>=? AND dia<=?",Integer.class,rid,monday,today);
            if(todayCount==0&&weekCount<3){db.update("INSERT INTO mymoney_popup(restaurante_id,dia) VALUES(?,?)",rid,today);show=true;}
        }
        out.put("mostrarPopup",show);return out;
    }
    private BigDecimal progress(long rid) {return money(db.queryForObject("SELECT COALESCE(SUM(valor),0) FROM mymoney_indicacoes WHERE indicador_id=? AND status='VALIDADA'",BigDecimal.class,rid));}

    /** Reads committed payment records from every existing payment path, including reconciliation. */
    @Transactional
    public void reconcile() {
        lock();
        var refs=db.queryForList("SELECT * FROM mymoney_indicacoes WHERE status IN ('PENDENTE','VALIDADA') ORDER BY id");
        Set<Long> sources=new LinkedHashSet<>();
        for(var ref:refs) {
            long referred=id(ref.get("indicado_id")),source=id(ref.get("indicador_id"));sources.add(source);
            Object payment=ref.get("primeiro_pagamento_id");
            if(payment!=null) {
                String status=db.queryForObject("SELECT status FROM pagamentos_mensalidade WHERE id=?",String.class,payment);
                if(!"PAGO".equals(status)) {
                    db.update("UPDATE mymoney_indicacoes SET status='ESTORNADA',motivo_revisao='Primeiro pagamento cancelado ou estornado' WHERE id=?",ref.get("id"));
                    db.update("UPDATE mymoney_premios SET revisao_necessaria=TRUE WHERE restaurante_id=?",source);
                    audit(source,"sistema","ESTORNO_INDICACAO","Indicação #"+ref.get("id")+"; benefícios já concedidos exigem revisão e PIX pendentes ficam bloqueados.");
                }
                continue;
            }
            Restaurante a=restaurants.findById(source).orElseThrow(),b=restaurants.findById(referred).orElseThrow();
            String reason=suspicion(a,b);
            if(!reason.isEmpty()&&ref.get("revisado_em")==null){db.update("UPDATE mymoney_indicacoes SET status='EM_REVISAO',motivo_revisao=? WHERE id=?",reason,ref.get("id"));continue;}
            var paid=db.queryForList("SELECT p.id,p.pago_em FROM pagamentos_mensalidade p JOIN assinaturas a ON a.restaurante_id=p.restaurante_id WHERE p.restaurante_id=? AND p.status='PAGO' AND p.valor>0 AND p.pago_em IS NOT NULL AND (a.trial_fim IS NULL OR p.pago_em>=a.trial_fim) AND a.status='ATIVA' ORDER BY p.pago_em,p.id LIMIT 1",referred);
            if(paid.isEmpty()) continue;
            BigDecimal amount=db.queryForObject("SELECT valor_indicacao FROM mymoney_config WHERE id=1",BigDecimal.class);
            db.update("UPDATE mymoney_indicacoes SET status='VALIDADA',primeiro_pagamento_id=?,valor=?,validado_em=CURRENT_TIMESTAMP WHERE id=?",paid.getFirst().get("id"),amount,ref.get("id"));
            audit(source,"sistema","INDICACAO_VALIDADA","Indicação #"+ref.get("id")+"; pagamento #"+paid.getFirst().get("id")+"; progresso R$"+amount);
        }
        syncWallet();
        for(long rid:sources) unlock(rid);
    }
    private void unlock(long rid) {
        if(!eligible(rid)) return;
        BigDecimal total=progress(rid);
        var metas=db.queryForList("SELECT * FROM mymoney_metas m WHERE ativo=TRUE AND patamar<=? AND NOT EXISTS (SELECT 1 FROM mymoney_premios p WHERE p.restaurante_id=? AND p.meta_id=m.id) ORDER BY patamar",total,rid);
        for(var goal:metas) {
            subscriptions.findLockedByRestauranteId(rid).orElseThrow();
            int months=((Number)goal.get("meses")).intValue();
            var sub=subscriptionService.concederMesesGratis(restaurants.findById(rid).orElseThrow(),months,"myMoney: patamar R$"+goal.get("patamar"));
            BigDecimal pix=money(goal.get("pix"));
            db.update("INSERT INTO mymoney_premios(restaurante_id,meta_id,patamar,progresso_desbloqueio,meses,pix,status,meses_aplicados_em,validade_apos,cobranca_apos) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?,?)",
                rid,goal.get("id"),goal.get("patamar"),total,months,pix,pix.signum()>0?"PIX_PENDENTE":"CONCEDIDO",sub.getValidaAte(),sub.getProximaCobranca());
            audit(rid,"sistema","PREMIO_DESBLOQUEADO","Meta #"+goal.get("id")+"; progresso R$"+total+"; meses "+months+"; PIX R$"+pix);
        }
    }
    @Transactional
    public void recipient(long rid,String name,String document,String key) {
        lock();if(!eligible(rid)&&db.queryForObject("SELECT COUNT(*) FROM mymoney_participantes WHERE restaurante_id=?",Integer.class,rid)==0) throw bad("Programa disponível para assinantes após o trial.");ensureParticipant(rid);
        name=text(name);key=text(key);document=text(document).replaceAll("\\D","");
        if(name.length()<3||name.length()>180||key.isEmpty()||key.length()>180||!document.matches("[0-9]{11}|[0-9]{14}")) throw bad("Informe nome, CPF/CNPJ e chave PIX válidos.");
        db.update("UPDATE mymoney_participantes SET pix_nome=?,pix_documento=?,pix_chave=? WHERE restaurante_id=?",name,document,key,rid);
        audit(rid,"proprietario:"+rid,"DADOS_PIX_ATUALIZADOS","Dados do beneficiário atualizados pelo proprietário.");
    }
    public Object admin(Long rid) {
        String where=rid==null?"":" WHERE i.indicado_id="+rid+" OR i.indicador_id="+rid;
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("indicacoes",db.queryForList("SELECT i.*,a.nome indicador_nome,b.nome indicado_nome,p.pago_em,p.status pagamento_status,p.valor pagamento_valor FROM mymoney_indicacoes i JOIN restaurantes a ON a.id=i.indicador_id JOIN restaurantes b ON b.id=i.indicado_id LEFT JOIN pagamentos_mensalidade p ON p.id=i.primeiro_pagamento_id"+where+" ORDER BY i.id DESC LIMIT 500"));
        out.put("premios",db.queryForList("SELECT p.*,r.nome,c.pix_nome,c.pix_documento,c.pix_chave FROM mymoney_premios p JOIN restaurantes r ON r.id=p.restaurante_id LEFT JOIN mymoney_participantes c ON c.restaurante_id=p.restaurante_id"+(rid==null?"":" WHERE p.restaurante_id="+rid)+" ORDER BY p.id DESC LIMIT 500"));
        out.put("saques",db.queryForList("SELECT s.*,r.nome FROM mymoney_saques s JOIN restaurantes r ON r.id=s.restaurante_id"+(rid==null?"":" WHERE s.restaurante_id="+rid)+" ORDER BY s.id DESC LIMIT 500"));
        out.put("metas",db.queryForList("SELECT * FROM mymoney_metas ORDER BY patamar"));out.put("config",db.queryForMap("SELECT * FROM mymoney_config WHERE id=1"));
        out.put("historico",db.queryForList("SELECT * FROM mymoney_auditoria"+(rid==null?"":" WHERE restaurante_id="+rid)+" ORDER BY id DESC LIMIT 100"));return out;
    }
    @Transactional
    public void review(long referral,boolean approve,String reason,String actor) {
        lock();if(text(reason).length()<5||text(reason).length()>240)throw bad("Registre o motivo da decisão (5 a 240 caracteres).");
        var r=db.queryForMap("SELECT * FROM mymoney_indicacoes WHERE id=?",referral);
        if(!"EM_REVISAO".equals(r.get("status"))) throw bad("A indicação não está aguardando revisão.");
        db.update("UPDATE mymoney_indicacoes SET status=?,motivo_revisao=?,revisado_por=?,revisado_em=CURRENT_TIMESTAMP WHERE id=?",approve?"PENDENTE":"REJEITADA",reason,actor,referral);
        audit(id(r.get("indicador_id")),actor,"REVISAO_INDICACAO","Indicação #"+referral+": "+(approve?"aprovada":"rejeitada")+"; "+reason);
    }
    @Transactional
    public void reviewAward(long award,String reason,String actor) {
        lock();if(text(reason).length()<10||text(reason).length()>240)throw bad("Registre a justificativa da revisão (10 a 240 caracteres).");
        var p=db.queryForMap("SELECT * FROM mymoney_premios WHERE id=?",award);
        if(!yes(p.get("revisao_necessaria")))throw bad("Prêmio não está em revisão.");
        db.update("UPDATE mymoney_premios SET revisao_necessaria=FALSE WHERE id=?",award);
        audit(id(p.get("restaurante_id")),actor,"PREMIO_REVISAO_RESOLVIDA","Prêmio #"+award+" mantido após revisão: "+reason);
    }
    @Transactional
    public void pay(long award,LocalDate expected,LocalDateTime paidAt,String receipt,String actor) {
        lock();var p=db.queryForMap("SELECT * FROM mymoney_premios WHERE id=?",award);long rid=id(p.get("restaurante_id"));
        if("PIX_PAGO".equals(p.get("status")))return;
        if(!"PIX_PENDENTE".equals(p.get("status"))||yes(p.get("revisao_necessaria")))throw bad("Este prêmio não está disponível para pagamento. Confira a revisão administrativa.");
        if(paidAt==null) {db.update("UPDATE mymoney_premios SET pix_previsto_em=? WHERE id=?",expected,award);audit(rid,actor,"PREVISAO_PIX","Prêmio #"+award+"; previsão "+expected);return;}
        if(paidAt.isAfter(LocalDateTime.now(ZONE))||paidAt.isBefore(((Timestamp)p.get("criado_em")).toLocalDateTime())||text(receipt).length()<5||text(receipt).length()>240)throw bad("Informe data real do pagamento e referência do comprovante (5 a 240 caracteres).");
        var c=db.queryForMap("SELECT * FROM mymoney_participantes WHERE restaurante_id=?",rid);
        if(text(c.get("pix_chave")).isEmpty())throw bad("O beneficiário precisa cadastrar os dados PIX.");
        db.update("UPDATE mymoney_premios SET status='PIX_PAGO',pix_pago_em=?,pix_pago_por=?,pix_comprovante=?,pix_chave_snapshot=?,pix_nome_snapshot=?,pix_documento_snapshot=? WHERE id=?",paidAt,actor,receipt,c.get("pix_chave"),c.get("pix_nome"),c.get("pix_documento"),award);
        audit(rid,actor,"PIX_MARCADO_PAGO","Prêmio #"+award+"; valor R$"+p.get("pix")+"; comprovante "+receipt);
    }
    @Transactional
    public void configure(BigDecimal value,List<Goal> goals,String actor) {
        lock();if(value==null||value.signum()<=0||value.compareTo(new BigDecimal("1000"))>0||value.scale()>2)throw bad("Valor de indicação inválido.");
        if(goals==null||goals.isEmpty()||goals.size()>10)throw bad("Informe de 1 a 10 metas.");
        Set<Long> ids=new HashSet<>();Set<BigDecimal> limits=new HashSet<>();
        for(var g:goals)if(g.id()<=0||g.patamar()==null||g.patamar().signum()<=0||g.patamar().compareTo(new BigDecimal("100000"))>0||g.meses()<1||g.meses()>24||g.pix()==null||g.pix().signum()<0||g.pix().compareTo(new BigDecimal("100000"))>0||!ids.add(g.id())||!limits.add(money(g.patamar())))throw bad("Metas inválidas ou repetidas.");
        db.update("UPDATE mymoney_config SET valor_indicacao=?,atualizado_em=CURRENT_TIMESTAMP WHERE id=1",value);
        for(var g:goals) {
            int exists=db.queryForObject("SELECT COUNT(*) FROM mymoney_metas WHERE id=?",Integer.class,g.id());
            if(exists==0)db.update("INSERT INTO mymoney_metas(id,patamar,meses,pix,ativo) VALUES(?,?,?,?,?)",g.id(),g.patamar(),g.meses(),g.pix(),g.ativo());
            else db.update("UPDATE mymoney_metas SET patamar=?,meses=?,pix=?,ativo=? WHERE id=?",g.patamar(),g.meses(),g.pix(),g.ativo(),g.id());
        }
        audit(null,actor,"REGRAS_ATUALIZADAS","Valor por nova validação: R$"+value+"; metas "+goals+". Benefícios existentes mantêm seus valores originais.");
    }
    private BigDecimal walletBalance(long rid) {
        return money(db.queryForObject("SELECT COALESCE(SUM(valor),0) FROM mymoney_movimentos WHERE restaurante_id=?",BigDecimal.class,rid));
    }
    private void movement(long rid,Long referral,Long withdrawal,String type,BigDecimal amount,String reason) {
        db.update("INSERT INTO mymoney_movimentos(restaurante_id,indicacao_id,saque_id,tipo,valor,saldo_apos,motivo) VALUES(?,?,?,?,?,?,?)",
                rid,referral,withdrawal,type,amount,walletBalance(rid).add(amount),reason);
    }
    private void syncWallet() {
        for(var ref:db.queryForList("SELECT i.* FROM mymoney_indicacoes i WHERE i.status='VALIDADA' AND NOT EXISTS (SELECT 1 FROM mymoney_movimentos m WHERE m.indicacao_id=i.id AND m.tipo='CREDITO_INDICACAO') ORDER BY i.id")) {
            movement(id(ref.get("indicador_id")),id(ref.get("id")),null,"CREDITO_INDICACAO",money(ref.get("valor")),"Primeira assinatura paga da indicação #"+ref.get("id"));
        }
        for(var ref:db.queryForList("SELECT i.* FROM mymoney_indicacoes i WHERE i.status='ESTORNADA' AND EXISTS (SELECT 1 FROM mymoney_movimentos m WHERE m.indicacao_id=i.id AND m.tipo='CREDITO_INDICACAO') AND NOT EXISTS (SELECT 1 FROM mymoney_movimentos m WHERE m.indicacao_id=i.id AND m.tipo='ESTORNO_INDICACAO') ORDER BY i.id")) {
            movement(id(ref.get("indicador_id")),id(ref.get("id")),null,"ESTORNO_INDICACAO",money(ref.get("valor")).negate(),"Estorno do primeiro pagamento da indicação #"+ref.get("id"));
        }
    }
    private Map<String,Object> walletSummary(long rid) {
        BigDecimal balance=walletBalance(rid);
        return Map.of("disponivel",balance.max(BigDecimal.ZERO),"ajustePendente",balance.min(BigDecimal.ZERO).abs(),
                "solicitado",db.queryForObject("SELECT COALESCE(SUM(valor),0) FROM mymoney_saques WHERE restaurante_id=? AND status='PIX_PENDENTE'",BigDecimal.class,rid),
                "recebido",db.queryForObject("SELECT COALESCE(SUM(valor),0) FROM mymoney_saques WHERE restaurante_id=? AND status='PIX_PAGO'",BigDecimal.class,rid));
    }
    @Transactional
    public Object withdraw(long rid,BigDecimal amount,String requestKey) {
        lock();
        try { UUID.fromString(requestKey); } catch(Exception e) {throw bad("Atualize a página antes de solicitar o saque.");}
        var existing=db.queryForList("SELECT id,restaurante_id,valor FROM mymoney_saques WHERE request_key=?",requestKey);
        if(!existing.isEmpty()) {
            var row=existing.getFirst();
            if(id(row.get("restaurante_id"))!=rid||amount==null||money(row.get("valor")).compareTo(amount)!=0)throw bad("Solicitação de saque inválida.");
            return Map.of("id",row.get("id"));
        }
        reconcile();
        if(amount==null||amount.signum()<=0||amount.scale()>2||amount.compareTo(walletBalance(rid))>0)throw bad("Confira o saldo disponível para saque.");
        var recipients=db.queryForList("SELECT pix_nome,pix_documento,pix_chave FROM mymoney_participantes WHERE restaurante_id=?",rid);
        if(recipients.isEmpty()||text(recipients.getFirst().get("pix_chave")).isEmpty())throw bad("Cadastre seus dados PIX antes de solicitar o saque.");
        var c=recipients.getFirst();
        db.update("INSERT INTO mymoney_saques(restaurante_id,request_key,valor,pix_nome_snapshot,pix_documento_snapshot,pix_chave_snapshot) VALUES(?,?,?,?,?,?)",
                rid,requestKey,amount,c.get("pix_nome"),c.get("pix_documento"),c.get("pix_chave"));
        long withdrawal=db.queryForObject("SELECT id FROM mymoney_saques WHERE request_key=?",Long.class,requestKey);
        movement(rid,null,withdrawal,"RESERVA_SAQUE",amount.negate(),"Saldo reservado para solicitação de PIX #"+withdrawal);
        audit(rid,"proprietario:"+rid,"SAQUE_SOLICITADO","Solicitação #"+withdrawal+"; R$"+amount);
        return Map.of("id",withdrawal);
    }
    @Transactional
    public void payWithdrawal(long withdrawal,LocalDate expected,LocalDateTime paidAt,String receipt,String actor) {
        lock();reconcile();
        var p=db.queryForMap("SELECT * FROM mymoney_saques WHERE id=?",withdrawal);long rid=id(p.get("restaurante_id"));
        if("PIX_PAGO".equals(p.get("status")))return;
        if(!"PIX_PENDENTE".equals(p.get("status")))throw bad("Esta solicitação não está pendente.");
        if(walletBalance(rid).signum()<0)throw bad("Há saldo a ajustar por estorno. Cancele a solicitação e peça um novo saque com o saldo atualizado.");
        if(paidAt==null) {db.update("UPDATE mymoney_saques SET previsto_em=? WHERE id=?",expected,withdrawal);audit(rid,actor,"PREVISAO_SAQUE","Saque #"+withdrawal+"; previsão "+expected);return;}
        if(paidAt.isAfter(LocalDateTime.now(ZONE))||paidAt.isBefore(((Timestamp)p.get("criado_em")).toLocalDateTime())||text(receipt).length()<5||text(receipt).length()>240)throw bad("Informe data real do PIX e referência do comprovante (5 a 240 caracteres).");
        db.update("UPDATE mymoney_saques SET status='PIX_PAGO',pago_em=?,pago_por=?,comprovante=? WHERE id=?",paidAt,actor,receipt,withdrawal);
        audit(rid,actor,"SAQUE_PAGO","Saque #"+withdrawal+"; R$"+p.get("valor")+"; comprovante "+receipt);
    }
    @Transactional
    public void cancelWithdrawal(long withdrawal,String reason,String actor) {
        lock();var p=db.queryForMap("SELECT * FROM mymoney_saques WHERE id=?",withdrawal);
        if("CANCELADO".equals(p.get("status")))return;
        if(!"PIX_PENDENTE".equals(p.get("status")))throw bad("Só é possível cancelar saque ainda não pago.");
        if(text(reason).length()<5||text(reason).length()>240)throw bad("Informe o motivo do cancelamento (5 a 240 caracteres).");
        long rid=id(p.get("restaurante_id"));
        db.update("UPDATE mymoney_saques SET status='CANCELADO',cancelado_em=CURRENT_TIMESTAMP,cancelado_por=?,motivo_cancelamento=? WHERE id=?",actor,reason,withdrawal);
        movement(rid,null,withdrawal,"LIBERACAO_SAQUE",money(p.get("valor")),"Cancelamento da solicitação #"+withdrawal+": "+reason);
        audit(rid,actor,"SAQUE_CANCELADO","Saque #"+withdrawal+"; "+reason);
    }

    public record Goal(long id,BigDecimal patamar,int meses,BigDecimal pix,boolean ativo) {}
}
