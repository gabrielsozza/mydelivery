package com.mydelivery.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mydelivery.model.Cliente;
import com.mydelivery.model.Pedido;
import com.mydelivery.model.Restaurante;
import com.mydelivery.repository.ClienteRepository;
import com.mydelivery.repository.PedidoRepository;
import com.mydelivery.repository.RestauranteRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Endpoints da aba "Clientes" — lista agregada por cliente com:
 *  - dados básicos (nome, telefone, endereço)
 *  - quantidade de pedidos no período
 *  - valor total gasto
 *  - última compra
 *  - itens dos pedidos
 *
 * Agregação feita em memória: pra restaurantes pequenos/médios (até alguns
 * milhares de clientes) é mais simples e rápido que uma query nativa.
 * Quando ficar grande, basta substituir por uma query SQL com GROUP BY.
 *
 * Filtros de período aplicados nos PEDIDOS (não nos clientes). Cliente com
 * 0 pedidos no período não aparece — exceto quando o filtro é "todos".
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ClienteController {

    private final ClienteRepository clienteRepo;
    private final PedidoRepository pedidoRepo;
    private final RestauranteRepository restauranteRepo;
    /** JDBC direto — usado no import CSV pra evitar problemas de Hibernate
     *  session state (rollback-only), OSIV e translation de exception que
     *  vinha derrubando o batch inteiro no 409 Conflict. Cada INSERT é
     *  auto-commit, isolado, sem passar pelo GlobalExceptionHandler. */
    private final JdbcTemplate jdbc;

    private static final ZoneId TZ = ZoneId.of("America/Sao_Paulo");

    /**
     * Lista clientes agregados com filtros de período.
     * @param periodo "hoje", "ontem", "7d", "15d", "30d", "todos" (default)
     * @param ini      ISO date opcional (override personalizado)
     * @param fim      ISO date opcional (override personalizado)
     */
    @GetMapping("/api/restaurante/clientes")
    @PreAuthorize("hasRole('RESTAURANTE')")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> listar(
            @AuthenticationPrincipal String email,
            @RequestParam(defaultValue = "todos") String periodo,
            @RequestParam(required = false) String ini,
            @RequestParam(required = false) String fim) {

        Restaurante r = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        Long restId = r.getId();

        // Define janela
        LocalDateTime de, ate;
        LocalDate hoje = LocalDate.now(TZ);
        switch (periodo) {
            case "hoje" -> { de = hoje.atStartOfDay(); ate = hoje.plusDays(1).atStartOfDay(); }
            case "ontem" -> { de = hoje.minusDays(1).atStartOfDay(); ate = hoje.atStartOfDay(); }
            case "7d" -> { de = hoje.minusDays(7).atStartOfDay(); ate = hoje.plusDays(1).atStartOfDay(); }
            case "15d" -> { de = hoje.minusDays(15).atStartOfDay(); ate = hoje.plusDays(1).atStartOfDay(); }
            case "30d" -> { de = hoje.minusDays(30).atStartOfDay(); ate = hoje.plusDays(1).atStartOfDay(); }
            case "custom" -> {
                de = ini != null ? LocalDate.parse(ini).atStartOfDay() : LocalDate.of(2000,1,1).atStartOfDay();
                ate = fim != null ? LocalDate.parse(fim).plusDays(1).atStartOfDay() : LocalDate.now(TZ).plusDays(1).atStartOfDay();
            }
            default -> { de = LocalDate.of(2000,1,1).atStartOfDay(); ate = hoje.plusDays(1).atStartOfDay(); }
        }

        // Carrega pedidos do período
        List<Pedido> pedidos = pedidoRepo.findByRestauranteIdAndPeriodo(restId, de, ate);

        // Agrupa por cliente (cliente pode estar null — pedido de mesa anônimo)
        Map<Long, Aggr> porCliente = new HashMap<>();
        for (Pedido p : pedidos) {
            Cliente c = p.getCliente();
            if (c == null) continue; // pedidos sem cliente (mesa) ficam fora
            Aggr a = porCliente.computeIfAbsent(c.getId(), k -> new Aggr(c));
            a.add(p);
        }

        // Se for "todos", inclui clientes sem pedidos no período (ex: importados)
        if ("todos".equals(periodo)) {
            for (Cliente c : clienteRepo.findByRestauranteIdOrderByNomeAsc(restId)) {
                porCliente.computeIfAbsent(c.getId(), k -> new Aggr(c));
            }
        }

        // Ordena: mais pedidos primeiro; em empate, maior valor
        List<Aggr> ordenados = new ArrayList<>(porCliente.values());
        ordenados.sort(Comparator
                .<Aggr>comparingInt(a -> -a.qtdPedidos)
                .thenComparing(a -> a.total.negate()));

        return ResponseEntity.ok(ordenados.stream().map(Aggr::toMap).toList());
    }

    /**
     * Importação CSV/XLS (parcial): body é a lista de clientes pra criar.
     * Campos ausentes são preservados como null. Telefone duplicado é ignorado
     * (não cria duplicata). Não é destrutivo.
     */
    @PostMapping("/api/restaurante/clientes/importar")
    @PreAuthorize("hasRole('RESTAURANTE')")
    public ResponseEntity<Map<String, Object>> importar(
            @AuthenticationPrincipal String email,
            @RequestBody List<Map<String, Object>> rows) {

        Restaurante rOuter = restauranteRepo.findByUsuarioEmail(email).orElseThrow();
        final Long restauranteId = rOuter.getId();
        log.info("[Clientes:import] INICIO restaurante={} rows={}", restauranteId, rows.size());
        // Import via JdbcTemplate (INSERT direto), sem passar por JPA/Hibernate.
        // Cada linha é auto-commit isolada: falha numa não polui as próximas
        // nem propaga pro GlobalExceptionHandler (que virava 409 Conflict pro
        // batch inteiro).
        int criados = 0, ignorados = 0, falhados = 0;
        for (Map<String, Object> row : rows) {
            String nome = str(row, "nome");
            String tel = telLimpo(str(row, "telefone"));
            if (nome == null || nome.isBlank()) { ignorados++; continue; }

            if (tel != null && !tel.isBlank()) {
                // Idempotente: se já existe por telefone+restaurante, pula
                try {
                    if (clienteRepo.findByTelefoneAndRestauranteId(tel, restauranteId).isPresent()) {
                        ignorados++;
                        continue;
                    }
                } catch (Exception ignore) { /* lookup best-effort */ }
            }
            // Endereço: aceita campo unificado "endereco" OU as partes separadas
            // (rua, numero, bairro, cidade, cep, estado, complemento, referencia).
            // Quando vem separado, monta um "endereco" legível pra retrocompat
            // com telas antigas. As colunas específicas também são preenchidas.
            String enderecoUnificado = str(row, "endereco");
            // Truncamento defensivo: os campos das colunas específicas têm limites
            // no @Column (endereco_estado=10, endereco_cep=10, etc). Row problemática
            // do CSV pode ter "estado" como "Parnamirim RN" (13 chars) ou "cep" com
            // ", nº 12" acidental — cortamos ao invés de falhar o batch inteiro.
            String rua      = trunc(str(row, "rua"),         200);
            String numero   = trunc(str(row, "numero"),       20);
            String bairro   = trunc(str(row, "bairro"),      120);
            String cidade   = trunc(str(row, "cidade"),      120);
            String estado   = trunc(str(row, "estado"),       10);
            String cep      = trunc(str(row, "cep"),          10);
            String complemento = trunc(str(row, "complemento"), 120);
            String referencia  = trunc(str(row, "referencia"), 200);
            // "anotacoes" da coluna CSV entra na referência do endereço se vier — evita
            // criar coluna nova só pra um caso de importação.
            String anotacoes = trunc(str(row, "anotacoes"), 200);
            if (referencia == null || referencia.isBlank()) referencia = anotacoes;
            if (enderecoUnificado == null || enderecoUnificado.isBlank()) {
                enderecoUnificado = montarEnderecoLegivel(rua, numero, complemento, bairro, cidade, estado, cep);
            }
            // endereco (unificado) sem @Column length = VARCHAR(255) no MySQL.
            enderecoUnificado = trunc(enderecoUnificado, 255);

            String nomeFinal = trunc(nome.trim(), 200);
            String emailFinal = trunc(str(row, "email"), 200);
            // INSERT via JdbcTemplate: cada UPDATE é auto-commit isolado. Sem
            // Hibernate session, sem OSIV, sem exception translation vindo do
            // proxy do JpaRepository. Exception cai DENTRO do try local. Testado
            // com a coluna criado_em populada manualmente (o Hibernate @CreationTimestamp
            // é ignorado quando não passa pelo EntityManager).
            try {
                jdbc.update(
                    "INSERT INTO clientes ("
                    + "restaurante_id, nome, telefone, email, endereco, "
                    + "endereco_rua, endereco_numero, endereco_complemento, endereco_bairro, "
                    + "endereco_referencia, endereco_cidade, endereco_estado, endereco_cep, "
                    + "total_pedidos, criado_em"
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)",
                    restauranteId, nomeFinal, tel, emailFinal, enderecoUnificado,
                    rua, numero, complemento, bairro,
                    referencia, cidade, estado, cep,
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.now())
                );
                criados++;
            } catch (Exception e) {
                falhados++;
                log.warn("[Clientes] linha ignorada nome={} tel={}: {}",
                        nomeFinal, tel, e.getMessage());
            }
        }
        log.info("[Clientes] import restaurante={} criados={} ignorados={} falhados={}",
                restauranteId, criados, ignorados, falhados);
        return ResponseEntity.ok(Map.of(
                "criados", criados,
                "ignorados", ignorados + falhados,
                "falhados", falhados));
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ── helpers ──

    private static String str(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        return v == null ? null : v.toString().trim();
    }
    private static String telLimpo(String s) {
        return s == null ? null : s.replaceAll("\\D", "");
    }

    /** Monta "Rua X, 123 — Complemento, Bairro, Cidade/UF, CEP" ignorando partes vazias.
     *  Usado quando o CSV traz endereço em colunas separadas e queremos manter
     *  o campo unificado `endereco` legível pras telas antigas. */
    private static String montarEnderecoLegivel(String rua, String numero, String complemento,
                                                 String bairro, String cidade, String estado, String cep) {
        StringBuilder sb = new StringBuilder();
        if (rua != null && !rua.isBlank()) {
            sb.append(rua.trim());
            if (numero != null && !numero.isBlank()) sb.append(", ").append(numero.trim());
        } else if (numero != null && !numero.isBlank()) {
            sb.append("nº ").append(numero.trim());
        }
        if (complemento != null && !complemento.isBlank()) {
            if (sb.length() > 0) sb.append(" — ");
            sb.append(complemento.trim());
        }
        if (bairro != null && !bairro.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(bairro.trim());
        }
        if (cidade != null && !cidade.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(cidade.trim());
            if (estado != null && !estado.isBlank()) sb.append("/").append(estado.trim());
        } else if (estado != null && !estado.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(estado.trim());
        }
        if (cep != null && !cep.isBlank()) {
            if (sb.length() > 0) sb.append(" · CEP ");
            else sb.append("CEP ");
            sb.append(cep.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Acumulador interno por cliente. */
    private static class Aggr {
        final Cliente cliente;
        int qtdPedidos = 0;
        BigDecimal total = BigDecimal.ZERO;
        LocalDateTime ultimaCompra = null;
        List<String> itens = new ArrayList<>();

        Aggr(Cliente c) { this.cliente = c; }

        void add(Pedido p) {
            qtdPedidos++;
            if (p.getTotal() != null) total = total.add(p.getTotal());
            if (p.getCriadoEm() != null && (ultimaCompra == null || p.getCriadoEm().isAfter(ultimaCompra))) {
                ultimaCompra = p.getCriadoEm();
            }
            if (p.getItens() != null) {
                for (var it : p.getItens()) {
                    String n = it.getNomeProduto();
                    if (n != null && !n.isBlank() && !itens.contains(n)) itens.add(n);
                }
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new HashMap<>();
            out.put("id", cliente.getId());
            out.put("nome", cliente.getNome());
            out.put("telefone", cliente.getTelefone());
            out.put("endereco", cliente.getEndereco());
            out.put("email", cliente.getEmail());
            out.put("qtdPedidos", qtdPedidos);
            out.put("totalGasto", total);
            out.put("ultimaCompra", ultimaCompra != null ? ultimaCompra.toString() : null);
            out.put("itens", itens.size() > 10 ? itens.subList(0, 10) : itens);
            return out;
        }
    }
}
