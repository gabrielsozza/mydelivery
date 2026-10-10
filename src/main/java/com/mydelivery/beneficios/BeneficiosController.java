package com.mydelivery.beneficios;

import com.mydelivery.repository.RestauranteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.Principal;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class BeneficiosController {
    private final MyCashService cash;
    private final MyMoneyService money;
    private final RestauranteRepository restaurants;
    // Lookup by the actual owner's user email; team membership alone does not grant financial access.
    private long owner(Principal p) { return restaurants.findByUsuarioEmail(p.getName()).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Área exclusiva do proprietário.")).getId(); }
    @GetMapping("/api/restaurante/mycash") @PreAuthorize("hasRole('RESTAURANTE')")
    public Object cash(Principal p) {return cash.config(owner(p));}
    public record CashConfig(Boolean ativo,List<Long> produtos,boolean todos,BigDecimal percentual) {}
    @PutMapping("/api/restaurante/mycash") @PreAuthorize("hasRole('RESTAURANTE')")
    public Object cash(Principal p,@RequestBody CashConfig b) {cash.configure(owner(p),b.ativo(),b.produtos(),b.todos(),b.percentual());return Map.of("ok",true);}
    @GetMapping("/api/cardapio/{slug}/mycash")
    public MyCashService.PublicProgram program(@PathVariable String slug) {return cash.publicProgram(restaurants.findBySlug(slug).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)).getId());}
    @PostMapping("/api/cardapio/{slug}/mycash")
    public Object wallet(@PathVariable String slug,@RequestBody MyCashService.Identity b) {return cash.wallet(restaurants.findBySlug(slug).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)).getId(),b);}
    @GetMapping("/api/restaurante/mymoney") @PreAuthorize("hasRole('RESTAURANTE')")
    public Object money(Principal p) {return money.dashboard(owner(p),false);}
    @PostMapping("/api/restaurante/mymoney/popup") @PreAuthorize("hasRole('RESTAURANTE')")
    public Object popup(Principal p) {return money.dashboard(owner(p),true);}
    public record Withdrawal(BigDecimal valor,String requestKey) {}
    @PostMapping("/api/restaurante/mymoney/saques") @PreAuthorize("hasRole('RESTAURANTE')")
    public Object withdraw(Principal p,@RequestBody Withdrawal b) {return money.withdraw(owner(p),b.valor(),b.requestKey());}
    public record Recipient(String nome,String documento,String chave) {}
    @PutMapping("/api/restaurante/mymoney/pix") @PreAuthorize("hasRole('RESTAURANTE')")
    public Object recipient(Principal p,@RequestBody Recipient b) {money.recipient(owner(p),b.nome(),b.documento(),b.chave());return Map.of("ok",true);}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> invalid(ResponseStatusException e) {return ResponseEntity.status(e.getStatusCode()).body(Map.of("erro",Objects.toString(e.getReason(),"Operação indisponível.")));}
}
