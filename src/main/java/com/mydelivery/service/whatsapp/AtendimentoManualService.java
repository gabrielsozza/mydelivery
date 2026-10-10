package com.mydelivery.service.whatsapp;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.ClienteRepository;
import lombok.RequiredArgsConstructor;

/** Solicitações explícitas ficam disponíveis em qualquer tela, mesmo após reinício. */
@Service
@RequiredArgsConstructor
public class AtendimentoManualService {
    private final JdbcTemplate jdbc;
    private final ClienteRepository clientes;
    public record Alerta(long id, String numero, String nome, LocalDateTime solicitadoEm) {}

    @Transactional
    public void registrar(Restaurante restaurante, String telefone) {
        String numero = telefone == null ? "" : telefone.replaceAll("\\D", "");
        if (numero.length() < 10 || numero.length() > 15) return;
        String nacional = numero.startsWith("55") && numero.length() >= 12 ? numero.substring(2) : numero;
        String nome = null;
        for (String variante : new java.util.LinkedHashSet<>(List.of(numero, nacional, "55"+nacional))) {
            var cliente = clientes.findFirstByTelefoneAndRestauranteIdOrderByIdAsc(variante, restaurante.getId());
            if (cliente.isPresent()) { nome = cliente.get().getNome(); break; }
        }
        // Uma solicitação pendente por telefone. Reentrega do webhook/reinício não duplica o alerta.
        jdbc.update("""
            INSERT INTO whatsapp_atendimento_alertas (restaurante_id,numero,nome,solicitado_em)
            SELECT ?,?,?,? WHERE NOT EXISTS (
              SELECT 1 FROM whatsapp_atendimento_alertas
              WHERE restaurante_id=? AND numero=? AND visualizado_em IS NULL AND solicitado_em>?)
            """, restaurante.getId(), numero, nome, LocalDateTime.now(), restaurante.getId(), numero,
                LocalDateTime.now().minusHours(24));
    }

    @Transactional(readOnly=true)
    public List<Alerta> pendentes(Long restauranteId) {
        return jdbc.query("""
            SELECT id,numero,nome,solicitado_em FROM whatsapp_atendimento_alertas
            WHERE restaurante_id=? AND visualizado_em IS NULL AND solicitado_em>?
            ORDER BY solicitado_em ASC,id ASC LIMIT 100
            """, (rs,n) -> new Alerta(rs.getLong("id"),rs.getString("numero"),rs.getString("nome"),
                rs.getTimestamp("solicitado_em").toLocalDateTime()),restauranteId,LocalDateTime.now().minusHours(24));
    }
    @Transactional
    public void visualizar(Long restauranteId, long id) {
        jdbc.update("UPDATE whatsapp_atendimento_alertas SET visualizado_em=? WHERE id=? AND restaurante_id=? AND visualizado_em IS NULL",
                LocalDateTime.now(),id,restauranteId);
    }
}
