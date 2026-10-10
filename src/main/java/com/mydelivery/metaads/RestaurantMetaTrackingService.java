package com.mydelivery.metaads;

import com.mydelivery.model.Pedido;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RestaurantMetaTrackingService {
    private static final Set<String> BROWSER_EVENTS = Set.of("PageView", "ViewContent", "AddToCart", "InitiateCheckout", "ClickOrderButton", "ViewCart", "Reorder");
    private final RestaurantMetaTrackingRepository configs;
    private final RestaurantMetaPurchaseEventRepository purchases;
    private final RestauranteRepository restaurants;
    private final PedidoRepository orders;
    private final MetaAdsCrypto crypto;
    private final RestaurantMetaCapiSender sender;
    private final RestaurantMetaEventLog events;

    public RestaurantMetaTrackingService(RestaurantMetaTrackingRepository configs,
            RestaurantMetaPurchaseEventRepository purchases, RestauranteRepository restaurants,
            PedidoRepository orders, MetaAdsCrypto crypto, RestaurantMetaCapiSender sender,
            RestaurantMetaEventLog events) {
        this.configs = configs; this.purchases = purchases; this.restaurants = restaurants;
        this.orders = orders; this.crypto = crypto; this.sender = sender; this.events = events;
    }

    public record Settings(String pixelId, boolean enabled, boolean tokenConfigured) {}
    public record Update(String pixelId, String accessToken, boolean enabled) {}
    public record BrowserEvent(String eventName, String eventId, Double value, String contentId, String fbp, String fbc) {}
    public record PurchaseResult(String eventId, boolean alreadyTracked) {}

    public Settings get(Long restaurantId) {
        return configs.findById(restaurantId).map(this::settings).orElse(new Settings("", false, false));
    }

    public Settings publicSettings(String slug) {
        var restaurant = restaurants.findBySlug(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var config = configs.findById(restaurant.getId()).orElse(null);
        return config != null && config.isEnabled() ? settings(config) : new Settings("", false, false);
    }

    private Settings settings(RestaurantMetaTracking config) {
        return new Settings(config.getPixelId(), config.isEnabled(), config.getTokenEncrypted() != null && !config.getTokenEncrypted().isBlank());
    }

    @Transactional
    public Settings save(Long restaurantId, Update update) {
        if (update == null || update.pixelId() == null || !update.pixelId().matches("[0-9]{5,30}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe um ID de Pixel válido");
        if (update.accessToken() != null && update.accessToken().length() > 3000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token muito longo");
        RestaurantMetaTracking config = configs.findById(restaurantId).orElseGet(RestaurantMetaTracking::new);
        config.setRestauranteId(restaurantId);
        config.setPixelId(update.pixelId());
        if (update.accessToken() != null && !update.accessToken().isBlank()) {
            if (!crypto.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Proteção do token indisponível");
            config.setTokenEncrypted(crypto.encrypt(update.accessToken().trim()));
        }
        config.setEnabled(update.enabled());
        config.setUpdatedAt(LocalDateTime.now());
        return settings(configs.save(config));
    }

    @Transactional
    public void remove(Long restaurantId) { configs.deleteById(restaurantId); }

    public void browserEvent(String slug, BrowserEvent event, String ip, String userAgent) {
        if (event == null || !BROWSER_EVENTS.contains(event.eventName())
                || event.eventId() == null || !event.eventId().matches("[a-zA-Z0-9_-]{8,100}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evento inválido");
        Long id = restaurants.findBySlug(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)).getId();
        var config = configs.findById(id).orElse(null);
        if (config == null || !config.isEnabled()) return;
        events.record(id, event.eventName(), event.eventId(), "BROWSER", event.value(), event.contentId());
        // O navegador envia o mesmo event_id ao Pixel; a Meta elimina a duplicidade.
        sender.send(id, event.eventName(), event.eventId(), event.value(), event.contentId(),
                null, event.fbp(), event.fbc(), ip, userAgent);
    }

    @Transactional
    public PurchaseResult purchase(String slug, Long orderId, String phoneCheck, String fbp, String fbc, String ip, String userAgent) {
        Long id = restaurants.findBySlug(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)).getId();
        Pedido order = orders.findById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!id.equals(order.getRestaurante().getId()) || order.getTipo() == Pedido.Tipo.MESA)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String expectedPhone = order.getCliente() == null ? "" : order.getCliente().getTelefone().replaceAll("\\D", "");
        String providedPhone = phoneCheck == null ? "" : phoneCheck.replaceAll("\\D", "");
        if (expectedPhone.isBlank() || !expectedPhone.equals(providedPhone)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (order.getStatus() == Pedido.Status.CANCELADO ||
                (order.getModoPagamento() == Pedido.ModoPagamento.ONLINE && !Boolean.TRUE.equals(order.getPago())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pedido ainda não concluído");
        return recordPurchase(id, order, fbp, fbc, ip, userAgent);
    }

    @Transactional
    public void trackConfirmedOrder(Long orderId) {
        Pedido order = orders.findById(orderId).orElse(null);
        if (order == null || order.getRestaurante() == null || order.getTipo() == Pedido.Tipo.MESA
                || order.getStatus() == Pedido.Status.CANCELADO
                || (order.getModoPagamento() == Pedido.ModoPagamento.ONLINE && !Boolean.TRUE.equals(order.getPago()))) return;
        Long id = order.getRestaurante().getId();
        var config = configs.findById(id).orElse(null);
        if (config == null || !config.isEnabled()) return;
        recordPurchase(id, order, null, null, null, null);
    }

    private PurchaseResult recordPurchase(Long id, Pedido order, String fbp, String fbc, String ip, String userAgent) {
        Long orderId = order.getId();
        var previous = purchases.findByRestauranteIdAndPedidoId(id, orderId);
        if (previous.isPresent()) return new PurchaseResult(previous.get().getEventId(), true);
        String eventId = "purchase_" + id + "_" + orderId;
        var saved = new RestaurantMetaPurchaseEvent();
        saved.setRestauranteId(id); saved.setPedidoId(orderId);
        saved.setEventId(eventId); saved.setCreatedAt(LocalDateTime.now());
        purchases.save(saved);
        events.record(id, "Purchase", eventId, "SERVER", order.getTotal() == null ? null : order.getTotal().doubleValue(),
                "order_" + orderId);
        String phone = order.getCliente() == null ? null : order.getCliente().getTelefone();
        sender.send(id, "Purchase", eventId, order.getTotal() == null ? null : order.getTotal().doubleValue(),
                "order_" + orderId, phone, fbp, fbc, ip, userAgent);
        return new PurchaseResult(eventId, false);
    }

    public void abandonedCart(Long restaurantId, Long cartId, String phone) {
        var config = configs.findById(restaurantId).orElse(null);
        if (config == null || !config.isEnabled()) return;
        events.record(restaurantId, "AbandonedCart", "abandoned_" + restaurantId + "_" + cartId,
                "SERVER", null, null);
        sender.send(restaurantId, "AbandonedCart", "abandoned_" + restaurantId + "_" + cartId,
                null, null, phone, null, null, null, null);
    }

}
