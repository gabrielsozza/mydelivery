package com.mydelivery.crm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor @Slf4j
public class CrmDispatchJob {
    private final CrmDispatchService service;
    @Scheduled(fixedDelay=5000,initialDelay=45000)
    public void tick() {
        try { Long id=service.claim();if(id!=null) { service.deliver(id); service.refreshHeartbeat(id); } }
        catch(Exception e) { log.error("[CRM] Fila comercial falhou: {}",e.getClass().getSimpleName()); }
    }
}
