package com.mydelivery.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuração do programa de fidelidade do restaurante (1:1).
 * O programa só fica ativo se ativo = true. O dono configura:
 * - Como acumular: a cada `valorPorPonto` reais gastos, o cliente ganha 1 ponto
 * - Recompensa única: ao atingir `pontosParaRecompensa` pontos, gera um cupom
 *   automático do tipo configurado (% / R$ / item grátis)
 * - Expiração: pontos vencem em `diasExpiracao` dias após serem ganhos
 */
@Entity
@Table(name = "programas_fidelidade")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProgramaFidelidade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "restaurante_id", nullable = false, unique = true)
    private Restaurante restaurante;

    @Builder.Default
    @Column(nullable = false)
    private Boolean ativo = false;

    // ── Regra de acúmulo ──
    // Default: cliente ganha 1 ponto a cada R$ 1,00 gasto
    @Builder.Default
    @Column(name = "valor_por_ponto", precision = 10, scale = 2, nullable = false)
    private BigDecimal valorPorPonto = new BigDecimal("1.00");

    // ── Recompensa ──
    @Builder.Default
    @Column(name = "pontos_para_recompensa", nullable = false)
    private Integer pontosParaRecompensa = 100;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "tipo_recompensa", nullable = false)
    private TipoRecompensa tipoRecompensa = TipoRecompensa.DESCONTO_FIXO;

    // Para PERCENT: valor em % (ex: 10 = 10%)
    // Para DESCONTO_FIXO: valor em R$ (ex: 15.00 = R$ 15 OFF)
    // Para ITEM_GRATIS: ignorado; usa `descricaoRecompensa`
    @Column(name = "valor_recompensa", precision = 10, scale = 2)
    private BigDecimal valorRecompensa;

    // Descrição livre — usada principalmente para ITEM_GRATIS
    // (ex: "Hambúrguer artesanal grátis no próximo pedido")
    @Column(name = "descricao_recompensa", length = 200)
    private String descricaoRecompensa;

    // ── Expiração ──
    @Builder.Default
    @Column(name = "dias_expiracao", nullable = false)
    private Integer diasExpiracao = 90;

    @CreationTimestamp
    private LocalDateTime criadoEm;

    @UpdateTimestamp
    private LocalDateTime atualizadoEm;

    public enum TipoRecompensa {
        DESCONTO_PERCENT,   // X% off
        DESCONTO_FIXO,      // R$ X off
        ITEM_GRATIS         // descrição livre (será mostrada ao cliente)
    }
}
