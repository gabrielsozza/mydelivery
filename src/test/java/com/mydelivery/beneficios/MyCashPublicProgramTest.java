package com.mydelivery.beneficios;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MyCashPublicProgramTest {
    JdbcTemplate db; MyCashService cash;
    @BeforeEach void setup() {
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE mycash_config(restaurante_id BIGINT, ativo BOOLEAN)");
        db.execute("CREATE TABLE produtos(id BIGINT, restaurante_id BIGINT)");
        db.execute("CREATE TABLE mycash_produtos(produto_id BIGINT, restaurante_id BIGINT, percentual DECIMAL(5,2))");
        db.update("INSERT INTO mycash_config VALUES(1,TRUE),(2,FALSE)");
        db.update("INSERT INTO produtos VALUES(10,1),(11,1),(12,1),(20,2)");
        db.update("INSERT INTO mycash_produtos VALUES(10,1,3),(11,1,5.5),(12,1,0),(20,2,90),(20,1,80)");
        cash=new MyCashService(db);
    }
    @Test void publishesOnlyPositiveRatesForProductsOwnedByThisRestaurant() {
        var p=cash.publicProgram(1);assertTrue(p.ativo());assertEquals(2,p.produtos().size());
        assertEquals(10,p.produtos().getFirst().produtoId());assertEquals(new BigDecimal("3.00"),p.produtos().getFirst().percentual());
        assertEquals(new BigDecimal("5.50"),p.produtos().get(1).percentual());
    }
    @Test void inactiveOrUnconfiguredStoreDoesNotAdvertiseCashback() {
        assertFalse(cash.publicProgram(2).ativo());assertTrue(cash.publicProgram(2).produtos().isEmpty());
        assertFalse(cash.publicProgram(99).ativo());assertTrue(cash.publicProgram(99).produtos().isEmpty());
    }
    @Test void configurationChangesAreVisibleWithoutChangingWalletsOrOrders() {
        db.update("UPDATE mycash_produtos SET percentual=7 WHERE produto_id=10");
        assertEquals(new BigDecimal("7.00"),cash.publicProgram(1).produtos().getFirst().percentual());
        db.update("UPDATE mycash_config SET ativo=FALSE WHERE restaurante_id=1");
        assertTrue(cash.publicProgram(1).produtos().isEmpty());
    }
}
