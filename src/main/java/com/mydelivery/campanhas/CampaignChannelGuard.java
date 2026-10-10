package com.mydelivery.campanhas;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.RequiredArgsConstructor;
@Component @RequiredArgsConstructor
public class CampaignChannelGuard {
    private final JdbcTemplate db;
    public void checkReconnect(String instance){
        Long count=db.queryForObject("SELECT COUNT(*) FROM campanha_controles c JOIN whatsapp_instances w ON w.restaurante_id=c.restaurante_id WHERE w.instance_name=? AND c.canal_restrito=true",Long.class,instance);
        if(count!=null&&count>0)throw new IllegalStateException("Canal sinalizado pelo provedor. Reconexão automática bloqueada até revisão técnica; não trocar sessão para continuar campanhas.");
    }
}
