package com.mydelivery.controller;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.RestauranteRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
class GoogleMeasurementControllerTest {
 @Test void preservesFirstAttributionAndClearsItOnRevocation() {
  var repo=mock(RestauranteRepository.class); var r=new Restaurante();
  when(repo.findByUsuarioEmail("owner")).thenReturn(Optional.of(r)); var c=new GoogleMeasurementController(repo);
  c.salvar("owner",new GoogleMeasurementController.Preference(true,"123.456",2)); var firstConsent=r.getGoogleConsentAt();
  c.salvar("owner",new GoogleMeasurementController.Preference(true,"789.012",2));
  assertThat(r.getGoogleClientId()).isEqualTo("123.456"); assertThat(r.getGoogleConsentAt()).isEqualTo(firstConsent);
  c.salvar("owner",new GoogleMeasurementController.Preference(false,null,2));
  assertThat(r.getGoogleClientId()).isNull(); assertThat(r.getGoogleConsentAt()).isNull();
  assertThat(r.getGoogleMeasurementConsent()).isFalse();
 }
 @Test void staffCannotOverwriteOwnersConsent() {
  var repo=mock(RestauranteRepository.class); when(repo.findByUsuarioEmail("staff")).thenReturn(Optional.empty());
  new GoogleMeasurementController(repo).salvar("staff",new GoogleMeasurementController.Preference(true,"123.456",2));
  verify(repo,never()).save(any());
 }
}
