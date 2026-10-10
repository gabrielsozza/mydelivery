package com.mydelivery.crm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Emergency manual-only mode for the institutional prospecting number. */
@Component
public class CrmOutboundPolicy {
    private final boolean manualOnly;

    public CrmOutboundPolicy(@Value("${crm.manual-only:true}") boolean manualOnly) {
        this.manualOnly = manualOnly;
    }

    public boolean manualOnly() { return manualOnly; }

    public void requireSystemSendAllowed() {
        if (manualOnly) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Envio pelo sistema pausado. Abra o WhatsApp e escreva a mensagem manualmente somente para contatos autorizados.");
    }

}
