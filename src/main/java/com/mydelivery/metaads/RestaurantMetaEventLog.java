package com.mydelivery.metaads;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RestaurantMetaEventLog {
    private final JdbcTemplate db;

    public RestaurantMetaEventLog(JdbcTemplate db) { this.db = db; }

    // event_id is shared by Pixel and CAPI, so one action is counted only once.
    public void record(Long restaurantId, String name, String eventId, String source, Double value, String contentId) {
        BigDecimal amount = value != null && Double.isFinite(value) && value >= 0 && value <= 9_999_999_999.99
                ? BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP) : null;
        db.update("INSERT IGNORE INTO restaurant_meta_event_log "
                + "(restaurante_id,event_id,event_name,event_source,value_amount,content_id,occurred_at) "
                + "VALUES (?,?,?,?,?,?,?)", restaurantId, eventId, name, source, amount,
                contentId == null ? null : contentId.substring(0, Math.min(100, contentId.length())), LocalDateTime.now());
    }

    public record Count(String name, long count, BigDecimal value) {}
    public record Day(LocalDate date, String name, long count) {}
    public record Event(String name, String source, BigDecimal value, String contentId, LocalDateTime occurredAt) {}
    public record Analytics(int days, LocalDate from, LocalDate through, List<Count> totals, List<Day> byDay,
                            long eventCount, BigDecimal purchaseValue) {}
    public record EventPage(List<Event> items, long total, int page, int pageSize) {}

    public Analytics analytics(Long restaurantId, int days) {
        int period = validDays(days);
        LocalDate through = LocalDate.now();
        LocalDate from = through.minusDays(period - 1L);
        LocalDateTime start = from.atStartOfDay(), end = through.plusDays(1).atStartOfDay();
        List<Count> totals = db.query("SELECT event_name,COUNT(*),COALESCE(SUM(value_amount),0) "
                + "FROM restaurant_meta_event_log WHERE restaurante_id=? AND occurred_at>=? AND occurred_at<? GROUP BY event_name",
                (rs, row) -> new Count(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3)), restaurantId, start, end);
        List<Day> byDay = db.query("SELECT DATE(occurred_at),event_name,COUNT(*) FROM restaurant_meta_event_log "
                + "WHERE restaurante_id=? AND occurred_at>=? AND occurred_at<? GROUP BY DATE(occurred_at),event_name "
                + "ORDER BY DATE(occurred_at)",
                (rs, row) -> new Day(rs.getDate(1).toLocalDate(), rs.getString(2), rs.getLong(3)), restaurantId, start, end);
        long eventCount = totals.stream().mapToLong(Count::count).sum();
        BigDecimal purchaseValue = totals.stream().filter(item -> "Purchase".equals(item.name()))
                .map(Count::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Analytics(period, from, through, totals, byDay, eventCount, purchaseValue);
    }

    public EventPage events(Long restaurantId, int days, String type, int page) {
        int period = validDays(days);
        int currentPage = Math.max(0, Math.min(page, 1000));
        LocalDateTime start = LocalDate.now().minusDays(period - 1L).atStartOfDay();
        LocalDateTime end = LocalDate.now().plusDays(1).atStartOfDay();
        boolean filtered = type != null && !type.isBlank() && !"all".equals(type);
        if (filtered && !List.of("PageView", "ViewContent", "AddToCart", "InitiateCheckout", "ClickOrderButton",
                "ViewCart", "Reorder", "Purchase", "AbandonedCart").contains(type))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Tipo de evento inválido");
        String where = " FROM restaurant_meta_event_log WHERE restaurante_id=? AND occurred_at>=? AND occurred_at<?"
                + (filtered ? " AND event_name=?" : "");
        Object[] args = filtered ? new Object[] {restaurantId, start, end, type} : new Object[] {restaurantId, start, end};
        Long total = db.queryForObject("SELECT COUNT(*)" + where, Long.class, args);
        Object[] pageArgs = java.util.Arrays.copyOf(args, args.length + 2);
        pageArgs[args.length] = 50;
        pageArgs[args.length + 1] = currentPage * 50;
        List<Event> items = db.query("SELECT event_name,event_source,value_amount,content_id,occurred_at" + where
                + " ORDER BY occurred_at DESC,event_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new Event(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getTimestamp(5).toLocalDateTime()), pageArgs);
        return new EventPage(items, total == null ? 0 : total, currentPage, 50);
    }

    private static int validDays(int days) { return days == 7 || days == 30 || days == 90 ? days : 30; }
}
