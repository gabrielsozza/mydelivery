package com.mydelivery.service.ifood;

import com.mydelivery.model.Restaurante;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Verifica a autorização da loja específica, não apenas o OAuth global do app. */
@Service
@RequiredArgsConstructor
public class IfoodConnectionService {
    public static final String SEM_AUTORIZACAO = "O iFood ainda não autorizou o myDelivery a acessar esta loja. "
            + "Solicite o acesso à loja na aba Permissões do aplicativo myDelivery no iFood Developer "
            + "e peça ao responsável para aceitar no Portal do Parceiro. Depois, consulte novamente.";
    private final IfoodMerchantClient merchantClient;

    public Map<String,Object> consultar(Restaurante restaurante) {
        var out = new LinkedHashMap<String,Object>();
        String id = restaurante.getIfoodMerchantId();
        boolean configurado = id != null && !id.isBlank();
        out.put("configurado", configurado);
        out.put("merchantId", id);
        out.put("conectado", false);
        out.put("ativo", false);
        out.put("recebimentoSolicitado", Boolean.TRUE.equals(restaurante.getIfoodIntegracaoAtiva()));
        out.put("statusIntegracao", "NAO_CONFIGURADO");
        if (!configurado) return out;
        try {
            var loja = exigirAutorizacao(id);
            out.put("conectado", true);
            out.put("ativo", Boolean.TRUE.equals(restaurante.getIfoodIntegracaoAtiva()));
            out.put("nomeLoja", loja.getOrDefault("name", ""));
            out.put("statusIntegracao", Boolean.TRUE.equals(restaurante.getIfoodIntegracaoAtiva()) ? "AUTORIZADO" : "PAUSADO");
        } catch (ResponseStatusException e) {
            out.put("statusIntegracao", e.getStatusCode().value() == 409 ? "SEM_AUTORIZACAO" : "INDISPONIVEL");
            out.put("mensagem", e.getReason());
        }
        return out;
    }

    public Map<String,Object> exigirAutorizacao(String merchantId) {
        try {
            var loja = merchantClient.detalhesMerchant(merchantId);
            if (loja == null || !merchantId.equalsIgnoreCase(String.valueOf(loja.get("id"))))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O iFood não confirmou os dados desta loja. Tente consultar novamente.");
            return loja;
        } catch (IfoodApiException e) {
            if (e.getStatusIfood() == 403 || e.getStatusIfood() == 404)
                throw new ResponseStatusException(HttpStatus.CONFLICT, SEM_AUTORIZACAO);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível verificar a autorização desta loja no iFood. Tente novamente.");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "A consulta ao iFood está indisponível. A conexão desta loja ainda não foi confirmada.");
        }
    }
}
