package com.mydelivery.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Zona de entrega circular pra cobrar por distância. Usado quando o
 * restaurante configura {@code modo_taxa = 'RAIO'}.
 *
 * <p>Cada zona é um raio a partir do restaurante com taxa fixa. Ordem
 * define prioridade — a primeira zona cujo {@code raioKm} for MAIOR
 * ou IGUAL à distância do cliente é aplicada. Ex: zonas 2km/R$3,
 * 4km/R$4, 6km/R$5 → cliente a 3km paga R$4.
 *
 * <p>Se cliente estiver fora de TODAS as zonas, sistema recusa entrega
 * (mesma UX do bairro sem taxa cadastrada).
 */
@Entity
@Table(name = "zonas_entrega", indexes = {
        @Index(name = "idx_zona_rest_ordem", columnList = "restaurante_id,ordem")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZonaEntrega {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "restaurante_id", nullable = false)
    private Restaurante restaurante;

    /** Raio em quilômetros (borda externa da zona). Ex: 2.5 = 2,5km. */
    @Column(name = "raio_km", precision = 6, scale = 2, nullable = false)
    private BigDecimal raioKm;

    /** Valor cobrado de entrega dentro dessa zona. */
    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal taxa;

    /**
     * Ordem crescente por raio — determina a busca "primeira zona cujo
     * raio {@code >=} distância". Dono ajusta manualmente ao arrastar
     * zonas no painel; se null, sistema reordena por raio ao salvar.
     */
    @Builder.Default
    @Column(nullable = false)
    private Integer ordem = 0;
}
