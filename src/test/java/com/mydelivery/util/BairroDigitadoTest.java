package com.mydelivery.util;

import com.mydelivery.model.BairroEntrega;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class BairroDigitadoTest {
    private BairroEntrega bairro(String nome) { return new BairroEntrega(nome, BigDecimal.TEN); }
    private final List<BairroEntrega> serra = List.of(bairro("Serra Dourada 1"),bairro("Serra Dourada 2"),bairro("Serra Dourada 3"));

    @ParameterizedTest @ValueSource(strings={"serra douada I","Serra Dourda 1","SERRA DOURADA I","serra duorada 1","serra douradaa 1"})
    void reconheceErrosPequenosERomanos(String digitado) {
        assertThat(BairroNormalizer.encontrar(serra,digitado).getNome()).isEqualTo("Serra Dourada 1");
    }
    @Test void naoConfundeNumerosOuNumeroOmitido() {
        assertThat(BairroNormalizer.encontrar(serra,"serra douada II").getNome()).isEqualTo("Serra Dourada 2");
        assertThat(BairroNormalizer.encontrar(serra,"serra douada 4")).isNull();
        assertThat(BairroNormalizer.encontrar(serra,"serra dourada")).isNull();
        assertThat(BairroNormalizer.encontrar(List.of(bairro("Serra Dourada 1")),"Serra Dourada 11")).isNull();
    }
    @Test void corrigeSaoJaoSemEscolherOutroBairroParecido() {
        var bairros = List.of(bairro("São João"), bairro("São José"), bairro("Arruda"));
        assertThat(BairroNormalizer.encontrar(bairros, "sao jao").getNome()).isEqualTo("São João");
        assertThat(BairroNormalizer.encontrar(bairros, "sao")).isNull();
    }
    @Test void nomesParecidosPedemNomeMaisPreciso() {
        var bairros=List.of(bairro("Jardim Lima"),bairro("Jardim Lira"));
        assertThat(BairroNormalizer.encontrar(bairros,"Jardim Lia")).isNull();
        assertThat(BairroNormalizer.encontrar(bairros,"Jardim Lima").getNome()).isEqualTo("Jardim Lima");
        assertThat(BairroNormalizer.encontrar(List.of(bairro("Parque Industrial"),bairro("Parque das Orquídeas")),"Parque")).isNull();
    }
    @Test void bairrosBissComErrosEAcentos() {
        var bairros=List.of(bairro("Parque Industrial"),bairro("Parque das Orquídeas"),bairro("Parque das orquídeas"),bairro("Emaús"));
        assertThat(BairroNormalizer.encontrar(bairros,"parqe industrial").getNome()).isEqualTo("Parque Industrial");
        assertThat(BairroNormalizer.encontrar(bairros,"parque das orquidas").getNome()).isEqualTo("Parque das Orquídeas");
        assertThat(BairroNormalizer.encontrar(bairros,"emaus").getNome()).isEqualTo("Emaús");
        assertThat(BairroNormalizer.encontrar(bairros,"Outra cidade")).isNull();
    }
}
