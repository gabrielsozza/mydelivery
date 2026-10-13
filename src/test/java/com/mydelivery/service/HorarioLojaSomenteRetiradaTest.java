package com.mydelivery.service;

import com.mydelivery.model.Restaurante;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HorarioLojaSomenteRetiradaTest {
    private final HorarioLojaService service = new HorarioLojaService();

    @Test
    void aplicaSomenteNosDiasMarcadosSemAlterarPausaManual() {
        Restaurante loja = new Restaurante();
        loja.setSomenteRetiradaAtivo(false);
        loja.setHorariosJson("{\"sex\":{\"aberto\":true,\"somenteRetirada\":true},"
                + "\"sab\":{\"aberto\":true,\"somenteRetirada\":true},"
                + "\"dom\":{\"aberto\":true,\"somenteRetirada\":false}}");

        assertThat(service.somenteRetiradaEm(loja, LocalDate.of(2026, 10, 16))).isTrue();
        assertThat(service.somenteRetiradaEm(loja, LocalDate.of(2026, 10, 17))).isTrue();
        assertThat(service.somenteRetiradaEm(loja, LocalDate.of(2026, 10, 18))).isFalse();
        assertThat(service.somenteRetiradaEm(loja, LocalDate.of(2026, 10, 19))).isFalse();

        loja.setSomenteRetiradaAtivo(true);
        assertThat(service.somenteRetiradaEm(loja, LocalDate.of(2026, 10, 18))).isTrue();
    }
}
