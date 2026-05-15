package com.mydelivery.dto.carrinho;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mydelivery.model.CarrinhoAbandonado;

public class CarrinhoAbandonadoResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Long id;
    private String sessionId;
    private String nomeCliente;
    private String telefoneCliente;
    private String itensJson;
    private BigDecimal valor;
    private String status;
    private LocalDateTime criadoEm;
    private LocalDateTime atualizadoEm;
    private LocalDateTime notificadoEm;

    public CarrinhoAbandonadoResponse(CarrinhoAbandonado c) {
        this.id               = c.getId();
        this.sessionId        = c.getSessionId();
        this.nomeCliente      = c.getNomeCliente();
        this.telefoneCliente  = c.getTelefoneCliente();
        this.itensJson        = c.getItensJson();
        this.valor            = calcularValor(c.getItensJson());
        this.status           = c.getStatus().name();
        this.criadoEm         = c.getCriadoEm();
        this.atualizadoEm     = c.getAtualizadoEm();
        this.notificadoEm     = c.getNotificadoEm();
    }

    /**
     * Soma quantidade × precoUnitario de cada item do JSON.
     * Falha silenciosamente (retorna ZERO) se o JSON for inválido — o frontend
     * já calcula localmente como fallback.
     */
    private static BigDecimal calcularValor(String itensJson) {
        if (itensJson == null || itensJson.isBlank()) return BigDecimal.ZERO;
        try {
            JsonNode arr = MAPPER.readTree(itensJson);
            if (!arr.isArray()) return BigDecimal.ZERO;
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode item : arr) {
                BigDecimal preco = item.hasNonNull("precoUnitario")
                        ? new BigDecimal(item.get("precoUnitario").asText("0"))
                        : BigDecimal.ZERO;
                int qty = item.hasNonNull("quantidade") ? item.get("quantidade").asInt(1) : 1;
                total = total.add(preco.multiply(BigDecimal.valueOf(qty)));
            }
            return total;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    // Getters
    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getNomeCliente() { return nomeCliente; }
    public String getTelefoneCliente() { return telefoneCliente; }
    public String getItensJson() { return itensJson; }
    public BigDecimal getValor() { return valor; }
    public String getStatus() { return status; }
    public LocalDateTime getCriadoEm() { return criadoEm; }
    public LocalDateTime getAtualizadoEm() { return atualizadoEm; }
    public LocalDateTime getNotificadoEm() { return notificadoEm; }
}
