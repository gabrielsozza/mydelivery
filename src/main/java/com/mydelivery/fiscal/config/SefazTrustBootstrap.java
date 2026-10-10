package com.mydelivery.fiscal.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** A conexão fiscal usa o truststore de CAs em NFeConfig; preserva a validação TLS do processo. */
@Slf4j
@Component
public class SefazTrustBootstrap {
    @PostConstruct
    public void instalar() {
        log.info("[Fiscal][SSL] Validação TLS e hostname preservadas; cadeia fiscal configurada por NFeConfig.");
    }
}
