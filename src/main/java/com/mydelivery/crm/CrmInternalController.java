package com.mydelivery.crm;

import com.mydelivery.model.Restaurante;
import com.mydelivery.model.WhatsappInstance;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.service.whatsapp.WhatsappService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import static com.mydelivery.crm.CrmIdentity.text;

@RestController
@RequestMapping("/api/admin-internal/crm")
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class CrmInternalController {
    private final CrmService crm;
    private final CrmDispatchService dispatch;
    private final CrmCommercialWhatsappService commercialWhatsapp;
    private final RestauranteRepository restaurants;
    private final WhatsappService whatsapp;
    CrmInternalController(CrmService crm,CrmDispatchService dispatch,RestauranteRepository restaurants,WhatsappService whatsapp) {
        this(crm,dispatch,null,restaurants,whatsapp);
    }
    @Value("${mydelivery.admin.internal-secret:${ADMIN_INTERNAL_SECRET:}}") private String secret;

    @ModelAttribute
    public void authorize(HttpServletRequest request) {
        if(secret==null||secret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Canal interno não configurado.");
        String supplied=request.getHeader("X-Admin-Secret");
        if(supplied==null||!MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Acesso negado.");
    }
    private String actor(HttpServletRequest r) { String a=text(r.getHeader("X-CRM-Actor"));return a.isBlank()?"admin":a.substring(0,Math.min(a.length(),160)); }
    @GetMapping("/config") public Object config() { return crm.config(); }
    @GetMapping("/commercial-whatsapp") public Object commercialInbox(@RequestParam long collaboratorId,@RequestParam(defaultValue="") String filter,@RequestParam(defaultValue="") String search) {
        return commercialWhatsapp.inbox(collaboratorId,filter,search);
    }
    @GetMapping("/commercial-whatsapp/status") public Object commercialConnection() { return commercialWhatsapp.connection(); }
    @GetMapping("/commercial-whatsapp/{leadId}") public Object commercialConversation(@RequestParam long collaboratorId,@PathVariable long leadId,@RequestParam(defaultValue="0") long before) {
        return commercialWhatsapp.conversation(collaboratorId,leadId,before);
    }
    @PostMapping("/commercial-whatsapp/{leadId}/read") public Object commercialRead(@RequestParam long collaboratorId,@PathVariable long leadId) {
        return commercialWhatsapp.read(collaboratorId,leadId);
    }
    public record CommercialWhatsappSend(long collaboratorId,String message,String requestKey) {}
    @PostMapping("/commercial-whatsapp/{leadId}/send") public ResponseEntity<?> commercialSend(@PathVariable long leadId,@RequestBody CommercialWhatsappSend b,HttpServletRequest r) {
        var result=commercialWhatsapp.send(b.collaboratorId(),leadId,b.message(),b.requestKey(),actor(r));
        // A rejected recipient is a failed request, including for clients that
        // were already open before the FAILED result was introduced.
        return ResponseEntity.status("FAILED".equals(result.get("status"))?HttpStatus.UNPROCESSABLE_ENTITY:HttpStatus.OK).body(result);
    }
    @PutMapping("/config") public Object config(@RequestBody Map<String,Object> b) { return crm.config(b); }
    @GetMapping("/leads") public Object list(@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String stage,@RequestParam(defaultValue="0") int page,
            @RequestParam(required=false) Long collaboratorId,@RequestParam(defaultValue="") String state,@RequestParam(required=false) List<String> city,@RequestParam(defaultValue="false") boolean unassigned) {
        return crm.list(search,stage,page,collaboratorId,state,city,unassigned);
    }
    @GetMapping("/leads/pipeline") public Object pipeline(@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String stage,@RequestParam(defaultValue="0") int page,
            @RequestParam(required=false) Long collaboratorId,@RequestParam(defaultValue="") String state,@RequestParam(required=false) List<String> city,@RequestParam(defaultValue="false") boolean unassigned) {
        return crm.pipeline(search,stage,page,collaboratorId,state,city,unassigned);
    }
    @PostMapping("/leads") public Object create(@RequestBody Map<String,Object> b,HttpServletRequest r) { return crm.create(b,actor(r)); }
    public record CommercialCreate(long collaboratorId,Map<String,Object> profile) {}
    @PostMapping("/leads/commercial") public Object commercialCreate(@RequestBody CommercialCreate b,HttpServletRequest r) {
        return crm.createForCollaborator(b.profile(),b.collaboratorId(),actor(r));
    }
    public record RegionAssignment(String city,List<String> cities,String state,String neighborhood,long collaboratorId,boolean includeAssigned) {}
    @PostMapping("/leads/assign-region") public Object assignRegion(@RequestBody RegionAssignment b,HttpServletRequest r) {
        List<String> cities=b.cities()==null||b.cities().isEmpty()?(b.city()==null?List.of():List.of(b.city())):b.cities();
        return crm.assignRegion(cities,b.state(),b.neighborhood(),b.collaboratorId(),b.includeAssigned(),actor(r));
    }
    @PostMapping("/leads/import") public Object importLeads(@RequestBody List<Map<String,Object>> items,HttpServletRequest r) {
        if(items.isEmpty()||items.size()>200) throw new IllegalArgumentException("Importe de 1 a 200 leads por arquivo.");
        List<Object> results=new ArrayList<>();
        for(int i=0;i<items.size();i++) {
            try { results.add(Map.of("linha",i+1,"lead",crm.create(items.get(i),actor(r)),"ok",true)); }
            catch(IllegalArgumentException|ResponseStatusException e) { results.add(Map.of("linha",i+1,"erro",e instanceof ResponseStatusException re?text(re.getReason()):text(e.getMessage()),"ok",false)); }
        }
        return Map.of("resultados",results);
    }
    @GetMapping("/leads/{id}") public Object detail(@PathVariable long id) { return crm.detail(id); }
    @DeleteMapping("/leads/{id}") public Object delete(@PathVariable long id) { crm.deleteLead(id); return Map.of("ok",true); }
    @GetMapping("/leads/{id}/commercial") public Object commercialDetail(@PathVariable long id,@RequestParam long collaboratorId) {return crm.commercialDetail(id,collaboratorId);}
    @GetMapping("/leads/{id}/commercial-history") public Object commercialHistory(@PathVariable long id,@RequestParam long collaboratorId,@RequestParam long before) {return crm.commercialHistory(id,collaboratorId,before);}
    public record CommercialEdit(long collaboratorId,Map<String,Object> profile,long version) {}
    @PutMapping("/leads/{id}/commercial-profile") public Object commercialUpdate(@PathVariable long id,@RequestBody CommercialEdit b,HttpServletRequest r) {
        if(b.profile()==null) throw new IllegalArgumentException("Perfil obrigatório.");
        return crm.commercialUpdate(id,b.collaboratorId(),b.profile(),b.version(),actor(r));
    }
    public record Assignment(Long collaboratorId) {}
    @PutMapping("/leads/{id}/assignment") public Object assign(@PathVariable long id,@RequestBody Assignment b,HttpServletRequest r) { return crm.assign(id,b.collaboratorId(),actor(r)); }
    public record Attempt(String channel,long collaboratorId) {}
    @PostMapping("/leads/{id}/attempt") public Object attempt(@PathVariable long id,@RequestBody Attempt b,HttpServletRequest r) { return crm.contactAttempt(id,b.channel(),b.collaboratorId(),actor(r)); }
    public record Appointment(long collaboratorId,String channel,String at,String note) {}
    @PostMapping("/leads/{id}/appointments") public Object schedule(@PathVariable long id,@RequestBody Appointment b,HttpServletRequest r) { return crm.schedule(id,b.collaboratorId(),b.channel(),b.at(),b.note(),actor(r)); }
    @PostMapping("/appointments/{id}/complete") public Object complete(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return crm.completeAppointment(id,((Number)b.get("collaboratorId")).longValue(),actor(r)); }
    public record Edit(Map<String,Object> profile,long version) {}
    @PutMapping("/leads/{id}") public Object update(@PathVariable long id,@RequestBody Edit b,HttpServletRequest r) { if(b.profile()==null) throw new IllegalArgumentException("Perfil obrigatório.");return crm.update(id,b.profile(),b.version(),actor(r)); }
    public record Change(String stage,String reason,long version) {}
    @PostMapping("/leads/{id}/stage") public Object stage(@PathVariable long id,@RequestBody Change b,HttpServletRequest r) { return crm.stage(id,b.stage(),b.reason(),b.version(),actor(r)); }
    @PostMapping("/leads/{id}/prepare") public Object prepare(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest r) { return crm.prepare(id,text(b.get("strategy")),actor(r)); }
    public record Message(String message,long version) {}
    @PutMapping("/leads/{id}/message") public Object message(@PathVariable long id,@RequestBody Message b,HttpServletRequest r) { return crm.saveMessage(id,b.message(),b.version(),actor(r)); }
    @PostMapping("/leads/{id}/note") public Object note(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest r) { crm.note(id,text(b.get("message")),actor(r));return Map.of("ok",true); }
    public record CommercialNote(long collaboratorId,String message) {}
    @PostMapping("/leads/{id}/commercial-note") public Object commercialNote(@PathVariable long id,@RequestBody CommercialNote b,HttpServletRequest r) {crm.commercialNote(id,b.collaboratorId(),b.message(),actor(r));return Map.of("ok",true);}
    public record CommercialMessage(long collaboratorId,String message,long version) {}
    @PutMapping("/leads/{id}/commercial-message") public Object commercialMessage(@PathVariable long id,@RequestBody CommercialMessage b,HttpServletRequest r) {
        return crm.commercialMessage(id,b.collaboratorId(),b.message(),b.version(),actor(r));
    }
    public record CommercialCall(long collaboratorId,String outcome,String summary,String objection,String nextAction,String nextAt,long version) {}
    @PostMapping("/leads/{id}/commercial-call") public Object commercialCall(@PathVariable long id,@RequestBody CommercialCall b,HttpServletRequest r) {
        return crm.commercialCall(id,b.collaboratorId(),b.outcome(),b.summary(),b.objection(),b.nextAction(),b.nextAt(),b.version(),actor(r));
    }
    public record CommercialChange(long collaboratorId,String stage,String reason,long version) {}
    @PostMapping("/leads/{id}/commercial-stage") public Object commercialStage(@PathVariable long id,@RequestBody CommercialChange b,HttpServletRequest r) {return crm.commercialStage(id,b.collaboratorId(),b.stage(),b.reason(),b.version(),actor(r));}
    @PostMapping("/leads/{id}/next-action") public Object next(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest r) { crm.nextAction(id,text(b.get("description")),text(b.get("at")),actor(r));return Map.of("ok",true); }
    @PostMapping("/leads/{id}/handoff") public Object handoff(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest r) { crm.handoff(id,text(b.get("state")),actor(r));return Map.of("ok",true); }
    @PostMapping("/leads/{id}/opt-out") public Object optOut(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest r) { crm.optOut(id,text(b.get("reason")),actor(r));return Map.of("ok",true); }
    public record Customer(long restaurantId) {}
    @PostMapping("/leads/{id}/customer") public Object customer(@PathVariable long id,@RequestBody Customer b,HttpServletRequest r) { crm.customer(id,b.restaurantId(),actor(r));return Map.of("ok",true); }
    @PostMapping("/leads/{id}/send") public Object send(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest r) { if(!(b.get("version") instanceof Number v)) throw new IllegalArgumentException("Versão obrigatória."); return dispatch.enqueue(id,text(b.get("kind")),text(b.get("requestKey")),v.longValue(),actor(r)); }
    @PostMapping("/leads/{id}/outbox/{queueId}/cancel") public Object cancel(@PathVariable long id,@PathVariable long queueId,HttpServletRequest r) { dispatch.cancel(id,queueId,actor(r));return Map.of("ok",true); }
    @PostMapping("/leads/{id}/outbox/{queueId}/resolve") public Object resolve(@PathVariable long id,@PathVariable long queueId,@RequestBody Map<String,Object> b,HttpServletRequest r) {
        if(!(b.get("wasSent") instanceof Boolean sent)) throw new IllegalArgumentException("Informe o resultado da conferência.");
        dispatch.resolve(id,queueId,sent,text(b.get("reason")),actor(r));return Map.of("ok",true);
    }
    @GetMapping("/leads/{id}/history") public Object history(@PathVariable long id,@RequestParam long before) { return crm.history(id,before); }
    @GetMapping("/metrics") public Object metrics(@RequestParam LocalDate start,@RequestParam LocalDate end) { return crm.metrics(start,end); }
    private Restaurante restaurant() { return restaurants.findById(ProspectingPolicy.STORE_ID).orElseThrow(()->new NoSuchElementException("Loja de prospecção não encontrada.")); }
    @GetMapping("/whatsapp") public Object status() { return session(whatsapp.status(restaurant())); }
    @PostMapping("/whatsapp/connect") public Object connect() { return session(whatsapp.conectar(restaurant(),true)); }
    @PostMapping("/whatsapp/disconnect") public Object disconnect() { whatsapp.desconectar(restaurant());return Map.of("ok",true); }
    private Object session(WhatsappInstance inst) {
        if(inst==null) return Map.of("storeId",ProspectingPolicy.STORE_ID,"status","NOVA");
        ProspectingPolicy.requireInstance(inst);
        Map<String,Object> out=new LinkedHashMap<>();out.put("storeId",ProspectingPolicy.STORE_ID);out.put("status",inst.getStatus());out.put("phone",inst.getPhone());out.put("qrCode",inst.getQrCode());out.put("qrExpiraEm",inst.getQrExpiraEm());return out;
    }
    @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class}) public ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("erro",text(e.getMessage()))); }
    @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<?> missing(Exception e) { return ResponseEntity.status(404).body(Map.of("erro",text(e.getMessage()))); }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> statusError(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("erro",text(e.getReason()))); }
}
