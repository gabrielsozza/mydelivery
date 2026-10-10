package com.mydelivery.service.whatsapp;

import com.mydelivery.repository.PromoCampanhaEnvioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PromoPrioridadeTest {
    @Test void priorizaRecorrentesUmaCompraZeroSemDuplicarNemReenviarEIsolaLojas() {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE promo_campanhas(id BIGINT PRIMARY KEY,restaurante_id BIGINT)");
        db.execute("CREATE TABLE promo_campanha_envios(id BIGINT PRIMARY KEY,campanha_id BIGINT,telefone VARCHAR(30),enviado_em TIMESTAMP)");
        db.execute("CREATE TABLE clientes(id BIGINT PRIMARY KEY,restaurante_id BIGINT,telefone VARCHAR(50),total_pedidos INT)");
        db.update("INSERT INTO promo_campanhas VALUES(1,58),(2,99)");
        db.update("INSERT INTO clientes VALUES(1,58,'84999990001',0),(2,58,'+55 (84) 99999-0002',1),(3,58,'84999990003',2),(4,58,'84999990004',10),(5,58,'84999990003',2),(6,99,'84999990001',999),(7,58,'55999990005',1)");
        db.update("INSERT INTO promo_campanha_envios VALUES(1,1,'84999990001',NULL),(2,1,'84999990002',NULL),(3,1,'5584999990003',NULL),(4,1,'84999990004',NULL),(5,1,'84999990004',CURRENT_TIMESTAMP),(6,2,'84999990004',NULL),(7,1,'5555999990005',NULL)");
        String sql=PromoCampanhaEnvioRepository.FILA_PRIORITARIA_SQL.replace(":campanhaId","?");
        var ids=db.query(sql,(rs,n)->rs.getLong("id"),1);
        assertThat(ids).containsExactly(4L,3L,2L,7L,1L);
        var limited=db.query(sql+" LIMIT 1",(rs,n)->rs.getLong("id"),1);
        assertThat(limited).containsExactly(4L);
    }
}
