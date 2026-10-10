package com.mydelivery.service;

import com.mydelivery.model.*;
import com.mydelivery.repository.*;
import com.mydelivery.dto.restaurante.ConfiguracaoRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ConfiguracaoMercadoPagoTest {
    @Test void salvaCredenciaisNaLojaCorretaEPreservaCamposNaoEnviados() {
        var restaurantes=mock(RestauranteRepository.class);
        var configs=mock(ConfiguracaoRestauranteRepository.class);
        var service=new ConfiguracaoService(restaurantes,configs,mock(HorarioLojaService.class));
        var loja=Restaurante.builder().id(33L).nome("Loja").build();
        var request=new ConfiguracaoRequest();request.setMpAccessToken(" token-da-loja ");
        request.setMpPublicKey("public-key");request.setMpWebhookSecret("secret");
        var captor=org.mockito.ArgumentCaptor.forClass(ConfiguracaoRestaurante.class);
        service.salvar(loja,request);verify(configs).save(captor.capture());
        var cfg=captor.getValue();assertThat(cfg.getRestaurante()).isSameAs(loja);
        assertThat(cfg.getMpAccessToken()).isEqualTo("token-da-loja");
        when(configs.findByRestauranteId(33L)).thenReturn(Optional.of(cfg));
        var update=new ConfiguracaoRequest();update.setMpPublicKey("public-key-nova");
        service.salvar(loja,update);
        assertThat(cfg.getMpAccessToken()).isEqualTo("token-da-loja");
        assertThat(cfg.getMpWebhookSecret()).isEqualTo("secret");
        assertThat(cfg.getMpPublicKey()).isEqualTo("public-key-nova");
    }
}
