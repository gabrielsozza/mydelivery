package com.mydelivery.service.whatsapp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import com.mydelivery.model.*;
import com.mydelivery.repository.*;

@ExtendWith(MockitoExtension.class)
class PromoImagemTest {
    @Mock PromoCampanhaRepository campanhaRepo;
    @Mock PromoCampanhaEnvioRepository envioRepo;
    @Mock PromoOptOutRepository optOutRepo;
    @Mock PedidoRepository pedidoRepo;
    @Mock ClienteRepository clienteRepo;
    @Mock WhatsappInstanceRepository instanceRepo;
    @InjectMocks PromoCampanhaService service;
    @Test void imagemAusenteMantemCampanhaSoComTexto() {
        assertThat(PromoCampanhaService.validarImagemUrl(null)).isNull();
        assertThat(PromoCampanhaService.validarImagemUrl(" ")).isNull();
    }
    @Test void rejeitaUrlsExternasOuMalformadas() {
        for (String url : List.of("http://localhost/test.jpg","https://res.cloudinary.com.evil.test/x/image/upload/p.jpg","https://res.cloudinary.com@evil.test/x/image/upload/p.jpg","https://res.cloudinary.com/x/raw/upload/p.pdf","blob:imagem","::::")) {
            assertThatThrownBy(() -> PromoCampanhaService.validarImagemUrl(url)).hasMessageContaining("Escolha uma imagem");
        }
    }
    @Test void entradaLegadaNaoContornaNovaElegibilidade() {
        Restaurante r = new Restaurante(); r.setId(1L);
        assertThatThrownBy(() -> service.criarEDisparar(r,"Promoção",1,"https://res.cloudinary.com/teste/image/upload/promo.webp"))
            .hasMessageContaining("Recrie esta campanha");
        verifyNoInteractions(campanhaRepo,envioRepo,instanceRepo);
    }
}
