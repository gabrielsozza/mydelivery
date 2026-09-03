package com.mydelivery.fiscal.model;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

/**
 * Log APPEND-ONLY de operações fiscais sensíveis.
 *
 * <p>NUNCA fazer UPDATE ou DELETE aqui — só INSERT. Serve pra investigar
 * incidentes ("quem trocou o cert da loja X?", "por que a nota Y foi
 * cancelada?"). Cada uso do certificado, cada emissão, cancelamento e
 * mudança de config vira uma linha.
 */
@Entity
@Table(name = "log_auditoria_fiscal")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogAuditoriaFiscal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "restaurante_id")
    private Long restauranteId;

    @Column(length = 20)
    private String cnpj;

    @Column(name = "usuario_email")
    private String usuarioEmail;

    @Column(nullable = false, length = 60)
    private String operacao;

    @Column(name = "detalhes_json", columnDefinition = "TEXT")
    private String detalhesJson;

    @Column(name = "ip_origem", length = 45)
    private String ipOrigem;

    /** OK, FALHA, NEGADO. */
    @Column(nullable = false, length = 20)
    private String resultado;

    @Column(name = "criado_em", updatable = false, insertable = false)
    private LocalDateTime criadoEm;
}
