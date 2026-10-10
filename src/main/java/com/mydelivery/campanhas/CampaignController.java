package com.mydelivery.campanhas;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.mydelivery.repository.RestauranteRepository;
import lombok.RequiredArgsConstructor;
@RestController @RequestMapping("/api/whatsapp/campanhas")
@PreAuthorize("hasRole('RESTAURANTE')") @RequiredArgsConstructor
public class CampaignController {
    final CampaignService service; final RestauranteRepository restaurants;
    long store(String email){return restaurants.findByUsuarioEmail(email).orElseThrow().getId();}
    @GetMapping("/config") public Object config(@AuthenticationPrincipal String email){return service.settings(store(email));}
    @PutMapping("/config") public Object config(@AuthenticationPrincipal String email,@RequestBody Map<String,Object>b){return service.saveSettings(store(email),b);}
    @PostMapping("/previa") public Object preview(@AuthenticationPrincipal String email,@RequestBody Map<String,Object>b){return service.preview(store(email),b);}
    @PostMapping public Object create(@AuthenticationPrincipal String email,@RequestBody Map<String,Object>b){return service.create(store(email),b);}
    @GetMapping public Object list(@AuthenticationPrincipal String email){return service.list(store(email));}
    @GetMapping("/{id}") public Object detail(@AuthenticationPrincipal String email,@PathVariable long id){return service.detail(store(email),id);}
    @GetMapping("/{id}/destinatarios") public Object recipients(@AuthenticationPrincipal String email,@PathVariable long id){return service.recipients(store(email),id);}
    @PostMapping("/{id}/iniciar") public Object start(@AuthenticationPrincipal String email,@PathVariable long id,@RequestBody(required=false) Map<String,Object>b){return service.start(store(email),id,b==null?null:Objects.toString(b.get("agendadaEm"),null));}
    @PostMapping("/{id}/pausar") public Object pause(@AuthenticationPrincipal String email,@PathVariable long id){return service.action(store(email),id,"pausar");}
    @PostMapping("/{id}/cancelar") public Object cancel(@AuthenticationPrincipal String email,@PathVariable long id){return service.action(store(email),id,"cancelar");}
    @GetMapping("/contatos") public Object contacts(@AuthenticationPrincipal String email){return service.contacts(store(email));}
    @PostMapping("/consentimento") public Object consent(@AuthenticationPrincipal String email,@RequestBody Map<String,Object>b){service.consent(store(email),b,email);return Map.of("ok",true);}
}
