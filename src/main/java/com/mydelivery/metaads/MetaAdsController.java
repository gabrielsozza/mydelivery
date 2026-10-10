package com.mydelivery.metaads;

import com.mydelivery.equipe.PermissaoContext;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import java.net.URI;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/restaurante/meta-ads")
public class MetaAdsController {
    private final RestauranteRepository restaurants;
    private final MetaAdsService ads;

    public MetaAdsController(RestauranteRepository restaurants, MetaAdsService ads) {
        this.restaurants = restaurants; this.ads = ads;
    }

    private Restaurante owner(String email) {
        var context = PermissaoContext.atual();
        if (context == null || !context.ehProprietario())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Somente o dono da loja gerencia anúncios");
        return restaurants.findByUsuarioEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
    }

    @GetMapping("/status") @PreAuthorize("hasRole('RESTAURANTE')")
    public Map<String, Object> status(@AuthenticationPrincipal String email) {
        return ads.status(owner(email).getId());
    }

    @PostMapping("/connect") @PreAuthorize("hasRole('RESTAURANTE')")
    public Map<String, String> connect(@AuthenticationPrincipal String email) {
        return Map.of("url", ads.authorizeUrl(owner(email).getId()));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code, @RequestParam(required = false) String error) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(ads.callback(state, code, error))).build();
    }

    @GetMapping("/assets") @PreAuthorize("hasRole('RESTAURANTE')")
    public Object assets(@AuthenticationPrincipal String email) { return ads.assets(owner(email).getId()); }

    public record AssetSelection(String accountId, String pageId) {}

    @PutMapping("/assets") @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Void> selectAssets(@AuthenticationPrincipal String email,
            @RequestBody AssetSelection selection) {
        ads.selectAssets(owner(email).getId(), selection.accountId(), selection.pageId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/campaigns") @PreAuthorize("hasRole('RESTAURANTE')")
    public List<MetaAdsCampaign> campaigns(@AuthenticationPrincipal String email) {
        return ads.campaigns(owner(email).getId());
    }

    @PostMapping("/campaigns") @PreAuthorize("hasRole('RESTAURANTE')")
    public MetaAdsCampaign draft(@AuthenticationPrincipal String email, @RequestBody MetaAdsService.Draft draft) {
        return ads.createDraft(owner(email), draft);
    }

    public record StatusChange(boolean active) {}

    @PutMapping("/campaigns/{id}/status") @PreAuthorize("hasRole('RESTAURANTE')")
    public MetaAdsCampaign status(@AuthenticationPrincipal String email, @PathVariable Long id,
            @RequestBody StatusChange request) {
        return ads.setStatus(owner(email).getId(), id, request.active());
    }

    @GetMapping("/campaigns/{id}/insights") @PreAuthorize("hasRole('RESTAURANTE')")
    public Object insights(@AuthenticationPrincipal String email, @PathVariable Long id) {
        return ads.insights(owner(email).getId(), id);
    }

    @DeleteMapping("/connection") @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal String email) {
        ads.disconnect(owner(email).getId());
        return ResponseEntity.noContent().build();
    }
}
