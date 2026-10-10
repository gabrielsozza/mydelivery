package com.mydelivery.service.whatsapp;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PromoCampanhaSenderTest {
 @Test void legacySenderCannotBeScheduledOrInstantiatedBySpring() {
   assertNull(PromoCampanhaSender.class.getAnnotation(org.springframework.stereotype.Component.class));
   assertEquals(0,PromoCampanhaSender.class.getDeclaredMethods().length);
 }
}
