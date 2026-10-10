package com.mydelivery.metaads;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mydelivery.model.Pedido;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RestaurantMetaTrackingServiceTest {
    private final RestaurantMetaTrackingRepository configs = mock(RestaurantMetaTrackingRepository.class);
    private final RestaurantMetaPurchaseEventRepository events = mock(RestaurantMetaPurchaseEventRepository.class);
    private final RestauranteRepository restaurants = mock(RestauranteRepository.class);
    private final PedidoRepository orders = mock(PedidoRepository.class);
    private final MetaAdsCrypto crypto = new MetaAdsCrypto(Base64.getEncoder().encodeToString(new byte[32]));
    private final RestaurantMetaCapiSender sender = mock(RestaurantMetaCapiSender.class);
    private final RestaurantMetaEventLog eventLog = mock(RestaurantMetaEventLog.class);
    private RestaurantMetaTrackingService service;

    @BeforeEach void setup() { service = new RestaurantMetaTrackingService(configs, events, restaurants, orders, crypto, sender, eventLog); }

    @Test void neverReturnsOrReplacesSavedTokenWithBlankInput() {
        var existing = new RestaurantMetaTracking(); existing.setRestauranteId(1L);
        existing.setPixelId("12345678"); existing.setTokenEncrypted(crypto.encrypt("secret-token"));
        when(configs.findById(1L)).thenReturn(Optional.of(existing));
        when(configs.save(any())).thenAnswer(call -> call.getArgument(0));
        var saved = service.save(1L, new RestaurantMetaTrackingService.Update("87654321", "", true));
        assertTrue(saved.tokenConfigured());
        assertEquals("secret-token", crypto.decrypt(existing.getTokenEncrypted()));
        assertEquals("87654321", saved.pixelId());
        assertFalse(saved.toString().contains("secret-token"));
    }

    @Test void refusesAnotherRestaurantsOrderAndUnpaidOnlineOrder() {
        var restaurant = new Restaurante(); restaurant.setId(1L);
        when(restaurants.findBySlug("loja-a")).thenReturn(Optional.of(restaurant));
        var other = new Restaurante(); other.setId(2L);
        var order = new Pedido(); order.setId(40L); order.setRestaurante(other);
        order.setTipo(Pedido.Tipo.DELIVERY); order.setTotal(new BigDecimal("20.00"));
        when(orders.findById(40L)).thenReturn(Optional.of(order));
        assertThrows(ResponseStatusException.class, () -> service.purchase("loja-a",40L,"11999999999",null,null,null,null));
        verifyNoInteractions(events);
    }
    @Test void serverTracksPaidOrderOnceAndSkipsUnpaidOnlineOrder() {
        var restaurant = new Restaurante(); restaurant.setId(1L);
        var order = new Pedido(); order.setId(40L); order.setRestaurante(restaurant);
        order.setTipo(Pedido.Tipo.DELIVERY); order.setModoPagamento(Pedido.ModoPagamento.ONLINE);
        order.setTotal(new BigDecimal("20.00")); order.setPago(false);
        when(orders.findById(40L)).thenReturn(Optional.of(order));
        service.trackConfirmedOrder(40L);
        verifyNoInteractions(events, sender);

        order.setPago(true);
        var config = new RestaurantMetaTracking(); config.setRestauranteId(1L);
        config.setPixelId("12345678"); config.setEnabled(true); config.setTokenEncrypted(crypto.encrypt("token"));
        when(configs.findById(1L)).thenReturn(Optional.of(config));
        when(events.findByRestauranteIdAndPedidoId(1L,40L)).thenReturn(Optional.empty());
        service.trackConfirmedOrder(40L);
        verify(events).save(any(RestaurantMetaPurchaseEvent.class));
        verify(eventLog).record(1L,"Purchase","purchase_1_40","SERVER",20.0,"order_40");
        verify(sender).send(eq(1L),eq("Purchase"),eq("purchase_1_40"),eq(20.0),eq("order_40"),
                isNull(),isNull(),isNull(),isNull(),isNull());
    }

}
