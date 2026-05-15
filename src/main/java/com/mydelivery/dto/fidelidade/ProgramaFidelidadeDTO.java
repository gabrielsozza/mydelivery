package com.mydelivery.dto.fidelidade;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mydelivery.model.ProgramaFidelidade;

import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProgramaFidelidadeDTO {
    private Boolean ativo;
    private BigDecimal valorPorPonto;
    private Integer pontosParaRecompensa;
    private String tipoRecompensa;          // DESCONTO_PERCENT | DESCONTO_FIXO | ITEM_GRATIS
    private BigDecimal valorRecompensa;
    private String descricaoRecompensa;
    private Integer diasExpiracao;

    public static ProgramaFidelidadeDTO fromEntity(ProgramaFidelidade p) {
        ProgramaFidelidadeDTO dto = new ProgramaFidelidadeDTO();
        if (p == null) {
            // Defaults para frontend quando ainda não há config salva
            dto.setAtivo(false);
            dto.setValorPorPonto(new BigDecimal("1.00"));
            dto.setPontosParaRecompensa(100);
            dto.setTipoRecompensa(ProgramaFidelidade.TipoRecompensa.DESCONTO_FIXO.name());
            dto.setValorRecompensa(new BigDecimal("15.00"));
            dto.setDescricaoRecompensa(null);
            dto.setDiasExpiracao(90);
            return dto;
        }
        dto.setAtivo(p.getAtivo());
        dto.setValorPorPonto(p.getValorPorPonto());
        dto.setPontosParaRecompensa(p.getPontosParaRecompensa());
        dto.setTipoRecompensa(p.getTipoRecompensa() != null ? p.getTipoRecompensa().name() : null);
        dto.setValorRecompensa(p.getValorRecompensa());
        dto.setDescricaoRecompensa(p.getDescricaoRecompensa());
        dto.setDiasExpiracao(p.getDiasExpiracao());
        return dto;
    }
}
