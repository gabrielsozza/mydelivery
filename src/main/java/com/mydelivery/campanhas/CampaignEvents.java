package com.mydelivery.campanhas;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.dao.DuplicateKeyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.mydelivery.model.WhatsappInstance;
import static com.mydelivery.campanhas.CampaignService.*;

@Service @RequiredArgsConstructor @Slf4j
public class CampaignEvents {
    final CampaignService s;
    public void inbound(WhatsappInstance instance,String jid,String id,String text,boolean fromMe,boolean byApi,Object timestamp){
        if(fromMe||id==null||id.isBlank()||id.length()>170)return;
        String phone=CampaignPolicy.phone(jid);if(phone==null)return;
        long rid=instance.getRestaurante().getId();if(com.mydelivery.crm.ProspectingPolicy.isStore(rid))return;
        LocalDateTime received=null;
        try{long ts=Long.parseLong(Objects.toString(timestamp,""));if(ts>10_000_000_000L)ts/=1000;received=LocalDateTime.ofInstant(Instant.ofEpochSecond(ts),CampaignPolicy.ZONE);}catch(Exception ignored){}
        final LocalDateTime eventTime=received;
        // Nunca concede consentimento via webhook público. Registra opt-out mesmo durante atendimento humano.
        try{s.tx.executeWithoutResult(t->{
            s.config(rid,true);
            int added=s.db.update("INSERT IGNORE INTO campanha_eventos(restaurante_id,chave,tipo,detalhe,criado_em) VALUES(?,?,'ENTRADA','Mensagem recebida; conteúdo não armazenado neste histórico',?)",rid,"in:"+id,now());if(added==0)return;
            s.db.update("INSERT IGNORE INTO campanha_contatos(restaurante_id,telefone) VALUES(?,?)",rid,phone);
            s.db.update("UPDATE campanha_contatos SET ultima_resposta=? WHERE restaurante_id=? AND telefone=?",now(),rid,phone);
            boolean out=CampaignPolicy.optOut(text),negative=CampaignPolicy.negative(text);
            if(out){
                s.db.update("INSERT IGNORE INTO promo_opt_outs(restaurante_id,telefone,motivo,criado_em) VALUES(?,?,'Pedido do cliente (linguagem natural)',?)",rid,phone,now());
                s.db.update("UPDATE campanha_destinos SET status='IGNORADO',motivo='Cliente solicitou não receber' WHERE restaurante_id=? AND telefone=? AND status='PENDENTE'",rid,phone);
            }
            // Timestamp ausente/antigo não vira resposta humana nem atribuição a campanha recente.
            if(eventTime==null||eventTime.isAfter(now().plusMinutes(2)))return;
            var rows=s.db.queryForList("SELECT d.*,c.atribuicao_dias FROM campanha_destinos d JOIN campanhas_crm c ON c.id=d.campanha_id AND c.restaurante_id=d.restaurante_id WHERE d.restaurante_id=? AND d.telefone=? AND d.aceito_em<=? ORDER BY d.aceito_em DESC,d.id DESC LIMIT 1",rid,phone,eventTime);
            if(rows.isEmpty())return;var d=rows.getFirst();LocalDateTime sent=time(d.get("aceito_em"));
            if(eventTime.isAfter(sent.plusDays(num(d,"atribuicao_dias"))))return;
            boolean human=CampaignPolicy.possibleHuman(text,Duration.between(sent,eventTime).getSeconds(),byApi);
            // Heurística: a interface identifica como provável, nunca como prova de resposta humana.
            if(human)s.db.update("UPDATE campanha_destinos SET resposta_em=COALESCE(resposta_em,?) WHERE id=?",eventTime,num(d,"id"));
            if(out)s.db.update("UPDATE campanha_destinos SET opt_out_em=COALESCE(opt_out_em,?) WHERE id=?",eventTime,num(d,"id"));
            if(negative)s.db.update("UPDATE campanha_destinos SET negativa_em=COALESCE(negativa_em,?) WHERE id=?",eventTime,num(d,"id"));
            long risk=s.db.queryForObject("SELECT COUNT(*) FROM campanha_destinos WHERE restaurante_id=? AND (opt_out_em>=? OR negativa_em>=?)",Long.class,rid,now().minusHours(s.policy.getJanelaSinaisHoras()),now().minusHours(s.policy.getJanelaSinaisHoras()));
            if(risk>=s.policy.getSinaisParaPausa())s.stop(rid,"Aumento de pedidos de não contato ou respostas negativas. Campanhas pausadas para revisão.",true);
        });}catch(DuplicateKeyException ignored){}
    }
    public void receipt(WhatsappInstance instance,Map<String,Object> message) {
        long rid=instance.getRestaurante().getId();
        String id=CampaignDispatcher.providerId(message),status=str(message,"status");
        if(id==null||!Set.of("Delivered","Read","Failed","Canceled").contains(status))return;
        s.tx.executeWithoutResult(t->{
            s.config(rid,true);
            var rows=s.db.queryForList("SELECT * FROM campanha_destinos WHERE restaurante_id=? AND (provider_id=? OR provider_internal_id=?) AND aceito_em IS NOT NULL",rid,id,id);
            for(var row:rows){
                if(Set.of("Delivered","Read").contains(status)){
                    s.db.update("UPDATE campanha_destinos SET entregue_em=COALESCE(entregue_em,?) WHERE id=?",now(),num(row,"id"));
                    s.db.update("UPDATE campanha_controles SET recibos_disponiveis=true WHERE restaurante_id=?",rid);
                }else if(row.get("entregue_em")==null){
                    s.db.update("UPDATE campanha_destinos SET status='FALHA',motivo='Provedor informou falha posterior à aceitação' WHERE id=?",num(row,"id"));
                    s.stop(rid,"Provedor informou falha de entrega. Campanhas pausadas para revisão.",false);
                }
            }
        });
    }
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void attribute(){
        try{attributeNow();}catch(Exception e){log.warn("[Campanhas] Atribuição pendente de nova tentativa ({})",e.getClass().getSimpleName());}
    }
    void attributeNow(){
        // Último contato aceito antes do pedido. Um pedido só pertence a uma campanha.
        // Mantém registro inclusive se cancelado depois; métricas excluem cancelados/não pagos em tempo real.
        s.db.update("""
            INSERT IGNORE INTO campanha_atribuicoes(pedido_id,campanha_id,destino_id,restaurante_id,criado_em)
            SELECT pedido_id,campanha_id,destino_id,restaurante_id,? FROM (
              SELECT p.id pedido_id,d.campanha_id,d.id destino_id,p.restaurante_id,
                ROW_NUMBER() OVER(PARTITION BY p.id ORDER BY d.aceito_em DESC,d.id DESC) ordem
              FROM pedidos p JOIN clientes cl ON cl.id=p.cliente_id AND cl.restaurante_id=p.restaurante_id
              JOIN campanha_destinos d ON d.restaurante_id=p.restaurante_id AND d.aceito_em IS NOT NULL AND d.status='ACEITO'
                AND (REGEXP_REPLACE(cl.telefone,'[^0-9]','')=d.telefone OR REGEXP_REPLACE(cl.telefone,'[^0-9]','')=CONCAT('55',d.telefone))
              JOIN campanhas_crm c ON c.id=d.campanha_id AND c.restaurante_id=p.restaurante_id
              LEFT JOIN campanha_atribuicoes a ON a.pedido_id=p.id
              WHERE a.pedido_id IS NULL AND p.criado_em>d.aceito_em
                AND p.criado_em<=TIMESTAMPADD(DAY,c.atribuicao_dias,d.aceito_em)
                AND p.pago=true AND p.status<>'CANCELADO'
                AND NOT EXISTS (SELECT 1 FROM pagamentos pg WHERE pg.pedido_id=p.id AND pg.status IN ('CANCELADO','EXPIRADO','RECUSADO'))
            ) candidatos WHERE ordem=1
            """,now());
    }
}
