package com.mydelivery.crm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.*;

@Component @RequiredArgsConstructor @Slf4j
public class CrmRegistrationListener {
    public record Registration(long restaurantId,String name,String phone,Long collaboratorId,Long leadId) {
        public Registration(long restaurantId,String name,String phone){this(restaurantId,name,phone,null,null);}
        public Registration(long restaurantId,String name,String phone,Long collaboratorId){this(restaurantId,name,phone,collaboratorId,null);}
    }
    private final CrmRegistrationService registrations;
    @Async
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void registered(Registration event) {
        try { registrations.match(event); }
        catch(RuntimeException e) { log.warn("[CRM] Associação de cadastro pendente de conferência: restaurante {}, {}",event.restaurantId(),e.getClass().getSimpleName()); }
    }
}
