package com.mydelivery.fiscal.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mydelivery.fiscal.model.LogAuditoriaFiscal;
import com.mydelivery.fiscal.repository.LogAuditoriaFiscalRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Serviço de auditoria APPEND-ONLY do módulo fiscal.
 *
 * <p>Grava cada operação sensível numa nova linha. Nunca atualiza nem deleta.
 * Usa {@code @Transactional(REQUIRES_NEW)} pra que a auditoria seja gravada
 * mesmo se a transação principal der rollback — é justamente quando dá erro
 * que a gente MAIS precisa do log.
 *
 * <p>Falha na auditoria NUNCA bloqueia a operação principal — só loga.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditoriaFiscalService {

    private final LogAuditoriaFiscalRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(Long restauranteId, String cnpj, String usuarioEmail,
                          String operacao, String resultado,
                          String ipOrigem, Map<String, Object> detalhes) {
        try {
            String json = null;
            if (detalhes != null && !detalhes.isEmpty()) {
                json = mapper.writeValueAsString(sanitizar(detalhes));
            }
            LogAuditoriaFiscal log = LogAuditoriaFiscal.builder()
                    .restauranteId(restauranteId)
                    .cnpj(cnpj)
                    .usuarioEmail(usuarioEmail)
                    .operacao(operacao)
                    .resultado(resultado == null ? "OK" : resultado)
                    .ipOrigem(ipOrigem)
                    .detalhesJson(json)
                    .build();
            repo.save(log);
        } catch (Exception e) {
            // Auditoria NUNCA quebra a operação — só loga o problema.
            log.error("[Fiscal][Auditoria] Falha ao gravar log (nao bloqueou operacao): {}", e.getMessage(), e);
        }
    }

    /** Remove/mascara chaves sensíveis pra nunca cair no banco. */
    private Map<String, Object> sanitizar(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().toLowerCase();
            if (k.contains("senha") || k.contains("password") || k.contains("csc")
                    || k.contains("pfx") || k.contains("cert") || k.contains("secret")) {
                out.put(e.getKey(), "***REDIGIDO***");
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
