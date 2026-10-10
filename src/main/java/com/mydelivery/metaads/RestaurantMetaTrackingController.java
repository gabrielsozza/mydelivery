package com.mydelivery.metaads;

import com.mydelivery.equipe.PermissaoContext;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class RestaurantMetaTrackingController {
    private final RestauranteRepository restaurants;
    private final RestaurantMetaTrackingService tracking;
    private final RestaurantMetaEventLog events;
    public RestaurantMetaTrackingController(RestauranteRepository restaurants, RestaurantMetaTrackingService tracking,
            RestaurantMetaEventLog events) {
        this.restaurants = restaurants; this.tracking = tracking; this.events = events;
    }
    private Restaurante owner(String email) {
        var context = PermissaoContext.atual();
        if (context == null || !context.ehProprietario())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Somente o dono da loja configura o Pixel");
        return restaurants.findByUsuarioEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @GetMapping("/api/restaurante/meta-tracking") @PreAuthorize("hasRole('RESTAURANTE')")
    public RestaurantMetaTrackingService.Settings settings(@AuthenticationPrincipal String email) { return tracking.get(owner(email).getId()); }
    @GetMapping("/api/restaurante/meta-tracking/analytics") @PreAuthorize("hasRole('RESTAURANTE')")
    public RestaurantMetaEventLog.Analytics analytics(@AuthenticationPrincipal String email,
            @RequestParam(defaultValue = "30") int days) { return events.analytics(owner(email).getId(), days); }
    @GetMapping("/api/restaurante/meta-tracking/analytics/events") @PreAuthorize("hasRole('RESTAURANTE')")
    public RestaurantMetaEventLog.EventPage eventLog(@AuthenticationPrincipal String email,
            @RequestParam(defaultValue = "30") int days, @RequestParam(defaultValue = "all") String type,
            @RequestParam(defaultValue = "0") int page) { return events.events(owner(email).getId(), days, type, page); }
    @PutMapping("/api/restaurante/meta-tracking") @PreAuthorize("hasRole('RESTAURANTE')")
    public RestaurantMetaTrackingService.Settings save(@AuthenticationPrincipal String email, @RequestBody RestaurantMetaTrackingService.Update update) {
        return tracking.save(owner(email).getId(), update);
    }
    @DeleteMapping("/api/restaurante/meta-tracking") @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal String email) { tracking.remove(owner(email).getId()); return ResponseEntity.noContent().build(); }
    @GetMapping("/api/cardapio/{slug}/meta-tracking")
    public RestaurantMetaTrackingService.Settings publicSettings(@PathVariable String slug) { return tracking.publicSettings(slug); }
    @PostMapping("/api/cardapio/{slug}/meta-tracking/events")
    public ResponseEntity<Void> event(@PathVariable String slug, @RequestBody RestaurantMetaTrackingService.BrowserEvent event, HttpServletRequest request) {
        tracking.browserEvent(slug, event, request.getRemoteAddr(), request.getHeader("User-Agent"));
        return ResponseEntity.accepted().build();
    }
    public record PurchaseRequest(String phone, String fbp, String fbc) {}
    @PostMapping("/api/cardapio/{slug}/meta-tracking/purchases/{orderId}")
    public RestaurantMetaTrackingService.PurchaseResult purchase(@PathVariable String slug, @PathVariable Long orderId,
            @RequestBody(required = false) PurchaseRequest data, HttpServletRequest request) {
        return tracking.purchase(slug, orderId, data == null ? null : data.phone(), data == null ? null : data.fbp(), data == null ? null : data.fbc(),
                request.getRemoteAddr(), request.getHeader("User-Agent"));
    }
}
