package com.mydelivery.util;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import com.mydelivery.model.BairroEntrega;
import static org.assertj.core.api.Assertions.*;

class CheckoutEnderecoTest {
    @Test void numeroComBlocoECasaNaoEstouraNemPerdeInformacao() {
        var original = Map.of("rua", " Rua Teste ", "numero", "123 bloco A casa dos fundos", "bairro", "Emaús");
        var endereco = EnderecoPedidoValidator.validar(original);
        assertThat(endereco.get("numero")).isEqualTo(original.get("numero"));
        assertThat(endereco.get("rua")).isEqualTo("Rua Teste");
        assertThat(original.get("rua")).isEqualTo(" Rua Teste ");
    }
    @Test void camposGrandesRetornamOrientacaoAntesDoBanco() {
        for (var campo : Map.of("rua",200,"numero",120,"complemento",120,"bairro",120,"referencia",200).entrySet()) {
            var endereco = new HashMap<>(Map.of("rua","Rua Teste","numero","12","bairro","Emaús"));
            endereco.put(campo.getKey(), "x".repeat(campo.getValue() + 1));
            assertThatThrownBy(() -> EnderecoPedidoValidator.validar(endereco))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("caracteres");
        }
        assertThatThrownBy(() -> EnderecoPedidoValidator.validar(Map.of("rua"," ","numero","12","bairro","Centro")))
            .hasMessageContaining("Rua / Avenida");
    }
    @Test void bairroExatoTemPrioridadeSobreNomeParecidoEAbreviacoesFuncionam() {
        var bairros = List.of(new BairroEntrega("Parque",BigDecimal.ONE),new BairroEntrega("Parque Industrial",BigDecimal.TEN),
                new BairroEntrega("Jardim São José II",BigDecimal.TWO));
        assertThat(BairroNormalizer.encontrar(bairros,"PARQUE INDUSTRIAL").getTaxa()).isEqualTo(BigDecimal.TEN);
        assertThat(BairroNormalizer.encontrar(bairros,"jd. sao jose 2").getTaxa()).isEqualTo(BigDecimal.TWO);
        assertThat(BairroNormalizer.encontrar(List.of(new BairroEntrega("Parque Industrial",BigDecimal.ONE),
                new BairroEntrega("Parque das Orquídeas",BigDecimal.TEN)),"Parque")).isNull();
    }
    @Test void migracaoCorrigeLimitesMantendoEnderecoCompleto() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:checkout_endereco;MODE=MySQL")) {
            var st=c.createStatement();
            st.execute("CREATE TABLE clientes (endereco_numero VARCHAR(20))");
            st.execute("CREATE TABLE pedidos (endereco_entrega VARCHAR(255))");
            String numero="123 bloco A casa dos fundos";
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO clientes VALUES ('"+numero+"')")).isInstanceOf(SQLException.class);
            String endereco="Rua dos testes "+"a".repeat(180)+", "+numero+" - Parque das Orquídeas (Ref: "+"b".repeat(150)+")";
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO pedidos VALUES ('"+endereco+"')")).isInstanceOf(SQLException.class);
            try(var in=getClass().getResourceAsStream("/db/migration/V18_3__checkout_endereco_limites.sql")) {
                for(String sql:new String(in.readAllBytes(),StandardCharsets.UTF_8).split(";")) if(!sql.isBlank()) st.execute(sql);
            }
            st.executeUpdate("INSERT INTO clientes VALUES ('"+numero+"')");
            st.executeUpdate("INSERT INTO pedidos VALUES ('"+endereco+"')");
            var rs=st.executeQuery("SELECT endereco_entrega FROM pedidos");rs.next();
            assertThat(rs.getString(1)).isEqualTo(endereco);
        }
    }
}
