package com.mydelivery.dto.admin;

import java.time.LocalDateTime;

import com.mydelivery.model.Restaurante;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RestauranteAdminResponse {
    private Long id;
    private String nome;
    private String slug;
    private String cnpj;
    private String emailDono;
    private String telefoneDono;
    private Restaurante.Status status;
    private LocalDateTime trialExpiraEm;
    private LocalDateTime bloqueadoEm;
    private String motivoBloqueio;
    private LocalDateTime criadoEm;
    private AssinaturaInfo assinatura;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AssinaturaInfo {
        private String status;
        private java.math.BigDecimal valor;
        private LocalDateTime proximaCobranca;
        private LocalDateTime trialFim;
    }
}