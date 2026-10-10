package com.mydelivery.metaads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

class RestaurantMetaEventLogTest {
    private RestaurantMetaEventLog log;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var db = new JdbcTemplate(dataSource);
        db.execute("CREATE TABLE restaurant_meta_event_log (restaurante_id BIGINT NOT NULL, event_id VARCHAR(100) NOT NULL, "
                + "event_name VARCHAR(40) NOT NULL, event_source VARCHAR(12) NOT NULL, value_amount DECIMAL(12,2), "
                + "content_id VARCHAR(100), occurred_at DATETIME(6) NOT NULL, PRIMARY KEY(restaurante_id,event_id))");
        log = new RestaurantMetaEventLog(db);
    }

    @Test
    void countsOnlyOneEventPerIdAndKeepsRestaurantsSeparate() {
        log.record(1L, "PageView", "view_12345678", "BROWSER", null, null);
        log.record(1L, "PageView", "view_12345678", "BROWSER", null, null);
        log.record(1L, "Purchase", "purchase_12345678", "SERVER", 23.50, "order_9");
        log.record(2L, "Purchase", "purchase_87654321", "SERVER", 99.00, "order_10");

        var report = log.analytics(1L, 30);
        assertEquals(2, report.eventCount());
        assertEquals(new BigDecimal("23.50"), report.purchaseValue());
        assertEquals(1, log.events(1L, 30, "Purchase", 0).total());
        assertEquals(2, log.events(1L, 30, "all", 0).total());
        assertEquals(1, log.events(2L, 30, "all", 0).total());
        assertThrows(ResponseStatusException.class, () -> log.events(1L, 30, "Unknown", 0));
    }
}
