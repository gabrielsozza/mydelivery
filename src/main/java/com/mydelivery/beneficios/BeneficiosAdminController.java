package com.mydelivery.beneficios;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin-internal/beneficios")
@RequiredArgsConstructor
public class BeneficiosAdminController {
    private final MyMoneyService money;
    @Value("${mydelivery.admin.internal-secret:${ADMIN_INTERNAL_SECRET:}}") private String secret;
    @ModelAttribute public void auth(HttpServletRequest request) {
        String supplied=request.getHeader("X-Admin-Secret");
        if(secret==null||secret.isBlank()||supplied==null||!MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Acesso negado.");
    }
    private String actor(HttpServletRequest r) {String a=BeneficiosSupport.text(r.getHeader("X-CRM-Actor"));return a.isEmpty()?"admin":a.substring(0,Math.min(160,a.length()));}
    @GetMapping("/mymoney") public Object dashboard(@RequestParam(required=false) Long restauranteId) {return money.admin(restauranteId);}
    public record Review(boolean aprovar,String motivo) {}
    @PostMapping("/mymoney/indicacoes/{id}/revisao") public Object review(@PathVariable long id,@RequestBody Review b,HttpServletRequest r){money.review(id,b.aprovar(),b.motivo(),actor(r));return Map.of("ok",true);}
    public record AwardReview(String motivo) {}
    @PostMapping("/mymoney/premios/{id}/revisao") public Object reviewAward(@PathVariable long id,@RequestBody AwardReview b,HttpServletRequest r){money.reviewAward(id,b.motivo(),actor(r));return Map.of("ok",true);}
    public record Payment(LocalDate previstoEm,LocalDateTime pagoEm,String comprovante) {}
    @PostMapping("/mymoney/premios/{id}/pagamento") public Object pay(@PathVariable long id,@RequestBody Payment b,HttpServletRequest r){money.pay(id,b.previstoEm(),b.pagoEm(),b.comprovante(),actor(r));return Map.of("ok",true);}
    @PostMapping("/mymoney/saques/{id}/pagamento") public Object payWithdrawal(@PathVariable long id,@RequestBody Payment b,HttpServletRequest r){money.payWithdrawal(id,b.previstoEm(),b.pagoEm(),b.comprovante(),actor(r));return Map.of("ok",true);}
    @PostMapping("/mymoney/saques/{id}/cancelamento") public Object cancelWithdrawal(@PathVariable long id,@RequestBody AwardReview b,HttpServletRequest r){money.cancelWithdrawal(id,b.motivo(),actor(r));return Map.of("ok",true);}
    public record Config(BigDecimal valorIndicacao,List<MyMoneyService.Goal> metas) {}
    @PutMapping("/mymoney/config") public Object config(@RequestBody Config b,HttpServletRequest r){money.configure(b.valorIndicacao(),b.metas(),actor(r));return Map.of("ok",true);}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> error(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("erro",Objects.toString(e.getReason(),"Operação indisponível.")));}
}
