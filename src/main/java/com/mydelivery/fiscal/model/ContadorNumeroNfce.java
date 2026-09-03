package com.mydelivery.fiscal.model;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

/**
 * Contador de numeração NFC-e por (CNPJ, série, ambiente).
 *
 * <p>A SEFAZ exige sequência SEM GAP nem duplicata. Se pular ou duplicar 1
 * número, o CNPJ trava emissão pra sempre até que seja feita
 * INUTILIZAÇÃO formal.
 *
 * <p>O service que usa este contador SEMPRE lê com {@code SELECT ... FOR UPDATE}
 * (lock pessimista) pra reservar o número ANTES de tentar emitir — elimina
 * race entre pedidos simultâneos.
 */
@Entity
@Table(name = "contador_numero_nfce",
       uniqueConstraints = @UniqueConstraint(name = "uq_contador", columnNames = {"cnpj","serie","ambiente"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContadorNumeroNfce {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String cnpj;

    @Column(nullable = false)
    @Builder.Default
    private Integer serie = 1;

    /** 1 = produção, 2 = homologação. */
    @Column(nullable = false)
    private Integer ambiente;

    @Column(name = "proximo_numero", nullable = false)
    @Builder.Default
    private Long proximoNumero = 1L;

    @Column(name = "atualizado_em", insertable = false, updatable = false)
    private LocalDateTime atualizadoEm;
}
