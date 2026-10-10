package com.mydelivery.service.whatsapp;
import com.mydelivery.model.*;
import com.mydelivery.repository.ClienteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AtendimentoManualServiceTest {
    ClienteRepository clientes=mock(ClienteRepository.class);
    JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    AtendimentoManualService service=new AtendimentoManualService(jdbc,clientes);
    AtendimentoManualServiceTest(){jdbc.execute("CREATE TABLE whatsapp_atendimento_alertas(id BIGINT AUTO_INCREMENT PRIMARY KEY,restaurante_id BIGINT,numero VARCHAR(20),nome VARCHAR(255),solicitado_em DATETIME(6),visualizado_em DATETIME(6))");}
    @Test void persisteNomeTelefoneSemDuplicarEIsolaLoja(){
        var loja=new Restaurante();loja.setId(10L);var cliente=new Cliente();cliente.setNome("Maria");
        when(clientes.findFirstByTelefoneAndRestauranteIdOrderByIdAsc("11912345678",10L)).thenReturn(Optional.of(cliente));
        service.registrar(loja,"5511912345678");service.registrar(loja,"5511912345678");
        var l=new AtendimentoManualService(jdbc,clientes).pendentes(10L);
        assertThat(l).hasSize(1);assertThat(l.getFirst().nome()).isEqualTo("Maria");assertThat(l.getFirst().numero()).isEqualTo("5511912345678");
        assertThat(service.pendentes(20L)).isEmpty();service.visualizar(20L,l.getFirst().id());assertThat(service.pendentes(10L)).hasSize(1);
        service.visualizar(10L,l.getFirst().id());assertThat(service.pendentes(10L)).isEmpty();
        service.registrar(loja,"5511912345678");assertThat(service.pendentes(10L)).hasSize(1);
    }
    @Test void desconhecidoTemTelefoneSemNomeInventado(){var r=new Restaurante();r.setId(11L);service.registrar(r,"5511912345678");assertThat(service.pendentes(11L).getFirst().nome()).isNull();}
}
