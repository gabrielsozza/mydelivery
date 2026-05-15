package com.mydelivery.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DashboardAdminResponse {
    private long totalRestaurantes;
    private long restaurantesAtivos;
    private long restaurantesEmTrial;
    private long restaurantesBloqueados;
    private long restaurantesCancelados;
    private java.math.BigDecimal receitaMensal;
    private long totalPedidosHoje;
}