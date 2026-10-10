package com.mydelivery.crm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class CrmStore {
    private final JdbcTemplate jdbc;
    public JdbcTemplate jdbc() { return jdbc; }
    private final ObjectMapper mapper;
    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    public LocalDateTime now() { return LocalDateTime.now(ZONE); }
    /** A singleton row serializes mutations/dispatch across API replicas. Always inside a transaction. */
    public Map<String,Object> lock() { return jdbc.queryForMap("SELECT * FROM crm_settings WHERE id=1 FOR UPDATE"); }
    public Map<String,Object> settings() { return jdbc.queryForMap("SELECT * FROM crm_settings WHERE id=1"); }
    public Map<String,Object> lead(long id) {
        var rows = jdbc.queryForList("SELECT * FROM crm_leads WHERE id=?", id);
        if (rows.isEmpty()) throw new NoSuchElementException("Lead não encontrado.");
        return rows.getFirst();
    }
    public Map<String,Object> profile(Map<String,Object> lead) {
        try { return mapper.readValue(lead.get("profile").toString(), new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("Perfil do lead inválido.", e); }
    }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Dados inválidos."); }
    }
    public long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i=0;i<args.length;i++) ps.setObject(i+1,args[i]);
            return ps;
        },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    public void event(long id, String type, Object detail, String actor) {
        jdbc.update("INSERT INTO crm_interactions(lead_id,type,detail,actor,created_at) VALUES (?,?,?,?,?)",
                id,type,json(detail),actor,now());
    }
    public static boolean bool(Object v) { return Boolean.TRUE.equals(v) || v instanceof Number n && n.intValue()!=0; }
    public static long number(Object v) { return v == null ? 0 : ((Number)v).longValue(); }
    public static LocalDateTime time(Object v) {
        if (v==null) return null;
        return v instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime)v;
    }
    public Map<String,Object> dto(Map<String,Object> row) {
        var out = new LinkedHashMap<>(row);
        if(out.containsKey("profile")) out.put("profile", profile(row));
        out.replaceAll((k,v) -> v instanceof Timestamp ts ? ts.toLocalDateTime().toString() : v);
        return out;
    }
}
