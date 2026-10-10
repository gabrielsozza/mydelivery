package com.mydelivery.service;

import com.mydelivery.dto.cupom.*;
import com.mydelivery.model.*;
import com.mydelivery.repository.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CupomExclusaoTest {
 final CupomRepository coupons=mock(CupomRepository.class);
 final CupomUsoRepository uses=mock(CupomUsoRepository.class);
 final PedidoRepository orders=mock(PedidoRepository.class);
 final CupomService service=new CupomService(coupons,uses,mock(RestauranteRepository.class),orders);
 Cupom coupon(){var r=new Restaurante();r.setId(58L);r.setSlug("teste");var c=Cupom.builder().id(48L).restaurante(r).codigo("BISS7OFF").tipo(Cupom.Tipo.PERCENT).valor(new BigDecimal("7")).build();when(coupons.findByIdForUpdate(48L)).thenReturn(Optional.of(c));return c;}
 @Test void usedCouponIsRemovedWithoutDeletingItsHistory(){var c=coupon();service.deletar(58L,48L);assertThat(c.getAtivo()).isFalse();assertThat(c.getExcluidoEm()).isNotNull();assertThat(c.getCodigo()).isEqualTo("BISS7OFF");verify(coupons).save(c);verify(coupons,never()).delete(any());verifyNoInteractions(uses,orders);}
 @Test void repeatedDeletionSucceedsWithoutChangingHistory(){var c=coupon();service.deletar(58L,48L);var at=c.getExcluidoEm();service.deletar(58L,48L);assertThat(c.getExcluidoEm()).isEqualTo(at);verify(coupons,times(1)).save(c);}
 @Test void cannotDeleteAnotherRestaurantsCoupon(){var c=coupon();assertThatThrownBy(()->service.deletar(99L,48L)).hasMessage("Acesso negado");assertThat(c.getAtivo()).isTrue();assertThat(c.getExcluidoEm()).isNull();verify(coupons,never()).save(any());}
 @Test void deletedCouponCannotBeReactivatedFromOldScreen(){var c=coupon();c.setExcluidoEm(LocalDateTime.now());var dto=new CupomDTO();dto.setAtivo(true);assertThatThrownBy(()->service.atualizar(58L,48L,dto)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verify(coupons,never()).save(any());}
 @Test void deletedCouponCannotBeAppliedEvenIfActiveFlagIsStale(){var c=coupon();c.setExcluidoEm(LocalDateTime.now());when(coupons.findByCodigoIgnoreCaseAndRestauranteSlug("BISS7OFF","teste")).thenReturn(Optional.of(c));var req=new ValidarCupomRequest();req.setCodigo("BISS7OFF");req.setSlug("teste");req.setSubtotal(new BigDecimal("100"));assertThat(service.validar(req).isValido()).isFalse();verifyNoInteractions(uses);}
 @Test void ordinaryCouponStillApplies(){var c=coupon();when(coupons.findByCodigoIgnoreCaseAndRestauranteSlug("BISS7OFF","teste")).thenReturn(Optional.of(c));var req=new ValidarCupomRequest();req.setCodigo("BISS7OFF");req.setSlug("teste");req.setSubtotal(new BigDecimal("100"));var result=service.validar(req);assertThat(result.isValido()).isTrue();assertThat(result.getDesconto()).isEqualByComparingTo("7.00");}
}
