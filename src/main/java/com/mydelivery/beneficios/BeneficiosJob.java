package com.mydelivery.beneficios;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
public class BeneficiosJob {
    private final MyCashService cash;
    private final MyMoneyService money;
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void reconcileCash() {
        for(Long rid:cash.restaurantsToReconcile()) {
            try {cash.reconcileRestaurant(rid);}catch(Exception e){log.error("[myCash] Falha na reconciliação da loja #{}; será repetida",rid,e);}
        }
    }
    @Scheduled(fixedDelay=60000,initialDelay=45000)
    public void reconcileMoney() {try {money.reconcile();}catch(Exception e){log.error("[myMoney] Falha na reconciliação; será repetida",e);}}
}
