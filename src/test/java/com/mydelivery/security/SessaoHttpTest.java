package com.mydelivery.security;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.mydelivery.repository.RestauranteRepository;
import com.mydelivery.equipe.MembroEquipeRepository;
import com.mydelivery.controller.HealthController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
@WebMvcTest(controllers=HealthController.class,properties="app.cors.allowed-origins=https://mydeliveryfood.com.br")
@org.springframework.test.context.ContextConfiguration(classes=HealthController.class)
@Import({SecurityConfig.class,JwtAuthenticationFilter.class,LoginRateLimitFilter.class})
class SessaoHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtUtil jwt;
    @MockitoBean RestauranteRepository lojas;
    @MockitoBean MembroEquipeRepository membros;
    @Test void sessaoAusenteOuExpiradaResponde401() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer expired")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/restaurante/qualquer")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/restaurante/qualquer").header("Authorization","Bearer expired")).andExpect(status().isUnauthorized());
    }
    @Test void permissaoInsuficienteContinua403() throws Exception {
        mvc.perform(get("/api/admin/qualquer").with(user("loja").roles("RESTAURANTE"))).andExpect(status().isForbidden());
    }
    @Test void healthContinuaPublico() throws Exception {mvc.perform(get("/api/health")).andExpect(status().isOk());}
}
