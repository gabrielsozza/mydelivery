package com.mydelivery.campanhas;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.mydelivery.repository.WhatsappInstanceRepository;
import com.mydelivery.service.whatsapp.UazapiClient;
import lombok.RequiredArgsConstructor;
import static com.mydelivery.campanhas.CampaignService.*;
/** Revisão privilegiada auditada. Nunca retoma a fila automaticamente. */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/admin/campanhas") @PreAuthorize("hasRole('ADMIN')")
public class CampaignReviewController {
    final CampaignService s; final WhatsappInstanceRepository instances; final UazapiClient provider;
    @PostMapping("/{rid}/revisar") public Object review(@AuthenticationPrincipal String actor,@PathVariable long rid,@RequestBody Map<String,Object>b){
        String evidence=str(b,"evidencia").trim();if(evidence.length()<20||evidence.length()>350)throw bad("Informe a evidência da revisão técnica (20 a 350 caracteres).");
        var inst=instances.findByRestauranteId(rid).orElseThrow();
        var diagnosis=provider.limitesCampanha(inst.getInstanceName(),inst.getInstanceToken());
        if(diagnosis==null||!Boolean.TRUE.equals(diagnosis.get("can_send_new_messages"))||CampaignDispatcher.restricted(diagnosis))throw bad("O provedor ainda não confirmou ausência de restrição. Campanhas continuam bloqueadas.");
        s.tx.executeWithoutResult(t->{s.config(rid,true);
            if(s.db.queryForObject("SELECT COUNT(*) FROM campanha_destinos WHERE restaurante_id=? AND status='PROCESSANDO'",Long.class,rid)>0)throw bad("Aguarde o término do processamento reservado.");
            s.db.update("UPDATE campanha_destinos SET revisado_em=? WHERE restaurante_id=? AND status='INCERTO'",now(),rid);
            s.db.update("UPDATE campanha_controles SET bloqueado=false,canal_restrito=false,motivo='Revisão técnica registrada. Campanhas permanecem pausadas.' WHERE restaurante_id=?",rid);
            s.audit(rid,"REVISAO_TECNICA",actor+": "+evidence);
        });return Map.of("ok",true,"aviso","Campanhas continuam pausadas. Resultados incertos não serão reenviados.");
    }
}
